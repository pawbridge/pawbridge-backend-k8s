package com.pawbridge.backend.community.internal.note.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.community.internal.note.application.RequireActiveNoteStream;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Manual executors/emitters verify registry policy; they do not model real sockets or servlet shutdown. */
class PrivateNoteStreamRegistryTest {
    @ParameterizedTest @ValueSource(strings = {"member", "global", "pending"})
    void givenConnectionBudget__whenOpeningBeyondIt__thenReturn429WithRetryAfterAndKeepExistingConnections(String budget) throws Exception {
        try (var f = new Fixture()) {
            if (budget.equals("member")) for (int n = 0; n < 3; n++) f.streams.open(7, f.expiry());
            else if (budget.equals("global")) for (long id = 1; id <= 500; id++) f.streams.open(id, f.expiry());
            else {
                UUID client = UUID.randomUUID();
                for (int n = 0; n < 4; n++) f.streams.open(7, f.expiry(), client);
                assertFull(() -> f.streams.open(7, f.expiry(), client));
                assertThat(f.emitters).hasSize(4);
                return;
            }
            assertFull(() -> f.streams.open(budget.equals("global") ? 501 : 7, f.expiry()));
            assertThat(f.emitters).hasSize(budget.equals("global") ? 500 : 3);
            assertThat(f.emitters).allSatisfy(emitter -> assertThat(emitter.completed.get()).isZero());
        }
    }

    @Test void givenSameClientReconnectAndLateOldCallback__whenResyncing__thenPreserveReplacementOtherTabsAndOtherMembers() throws Exception {
        try (var f = new Fixture()) {
            UUID tab = UUID.randomUUID();
            f.streams.open(7, f.expiry(), tab); var old = f.emitters.getFirst();
            f.streams.open(8, f.expiry(), tab);
            f.streams.open(7, f.expiry(), UUID.randomUUID());
            f.streams.open(7, f.expiry(), tab);
            f.completions.runAll(); old.completion.run();
            f.streams.resync(7); f.writes.runAll();
            assertThat(old.completed).hasValue(1); assertThat(old.sends).hasValue(1);
            assertThat(f.emitters.get(1).sends).hasValue(1);
            assertThat(f.emitters.get(2).sends).hasValue(2);
            assertThat(f.emitters.get(3).sends).hasValue(2);
            f.streams.open(7, f.expiry(), UUID.randomUUID());
            assertFull(() -> f.streams.open(7, f.expiry()));
        }
    }

    @ParameterizedTest @ValueSource(longs = {12, 600})
    void givenVerifiedJwtLifetime__whenOpening__thenUseTokenOrFiveMinuteCapAndOneFifteenSecondHeartbeat(long seconds) throws Exception {
        try (var f = new Fixture()) {
            assertThat(f.streams.open(7, f.clock.instant().plusSeconds(seconds)).getTimeout())
                    .isEqualTo(Math.min(seconds, 300) * 1000);
            verify(f.heartbeat).scheduleAtFixedRate(any(Runnable.class), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
        }
    }

    @Test void givenMemberReadCrossesVerifiedExpiry__whenOpening__thenFinishMemberQueryButAdmitNoEmitter() throws Exception {
        try (var f = new Fixture()) {
            Instant expiry = f.clock.instant().plusSeconds(1);
            var members = mock(ContactMemberQuery.class);
            when(members.get(7)).thenAnswer(call -> { f.clock.advance(2); return new ContactMember(7L, "", true, false); });
            var authentication = new UsernamePasswordAuthenticationToken(7L, null, List.of()); authentication.setDetails(expiry);
            var controller = new NoteStreamController(new RequireActiveNoteStream(members), f.streams);
            assertThatThrownBy(() -> controller.open(7L, authentication, null))
                    .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode().value()).isEqualTo(401));
            verify(members).get(7); verifyNoMoreInteractions(members); assertThat(f.emitters).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void givenQueuedHintOrHeldSpringWriteLock__whenTokenExpiresBeforeActualSend__thenSuppressResyncAndCompleteConnection(boolean locked) throws Exception {
        try (var f = new Fixture()) {
            f.streams.open(7, f.clock.instant().plusSeconds(1)); var emitter = f.emitters.getFirst();
            f.streams.resync(7);
            if (locked) {
                var worker = Executors.newSingleThreadExecutor(); emitter.lock();
                try {
                    var task = worker.submit(f.writes::runAll);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    while (!emitter.writerWaiting() && System.nanoTime() < deadline) Thread.onSpinWait();
                    assertThat(emitter.writerWaiting()).isTrue();
                    f.clock.advance(2); emitter.unlock(); task.get(1, TimeUnit.SECONDS);
                } finally {
                    if (emitter.lockOwned()) emitter.unlock();
                    worker.shutdownNow(); assertThat(worker.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
                }
            } else { f.clock.advance(2); f.writes.runAll(); }
            f.completions.runAll(); assertThat(emitter.sends).hasValue(1); assertThat(emitter.completed).hasValue(1);
        }
    }

    @Test void givenRejectedCompletion__whenHeartbeatRetries__thenKeepClosingSlotCountedAndSuppressAlreadyQueuedHint() throws Exception {
        try (var f = new Fixture()) {
            f.streams.open(7, f.clock.instant().plusSeconds(1)); f.streams.resync(7); f.clock.advance(2);
            f.completions.rejectNext = true; f.streams.heartbeat();
            f.streams.open(7, f.expiry()); f.streams.open(7, f.expiry()); assertFull(() -> f.streams.open(7, f.expiry()));
            f.writes.runAll(); assertThat(f.emitters.getFirst().sends).hasValue(1);
            f.streams.heartbeat(); f.completions.runAll(); f.streams.open(7, f.expiry());
            assertThat(f.emitters.getFirst().completed).hasValue(1);
        }
    }

    @Test void givenFullWriteQueue__whenAnotherHintArrives__thenDropOnlyHintWithoutUnboundedQueueOrThrowing() throws Exception {
        try (var f = new Fixture()) {
            f.writes.capacity = 1; f.streams.open(7, f.expiry());
            f.streams.resync(7); assertThatCode(() -> f.streams.resync(7)).doesNotThrowAnyException();
            assertThat(f.writes.tasks).hasSize(1); f.writes.runAll(); assertThat(f.emitters.getFirst().sends).hasValue(2);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"error", "timeout"})
    void givenDisconnectOrTimeoutCallback__whenCleaningConnection__thenReleaseOnlyItsSlotAndSuppressQueuedWrites(String callback) throws Exception {
        try (var f = new Fixture()) {
            for (int n = 0; n < 3; n++) f.streams.open(7, f.expiry());
            var closing = f.emitters.getFirst(); f.streams.resync(7);
            if (callback.equals("error")) closing.error.accept(new IOException("synthetic-disconnect"));
            else { closing.timeout.run(); assertFull(() -> f.streams.open(7, f.expiry())); f.completions.runAll(); }
            f.streams.open(7, f.expiry()); f.writes.runAll();
            assertThat(closing.sends).hasValue(1);
            assertThat(closing.completed).hasValue(callback.equals("error") ? 0 : 1);
            assertFull(() -> f.streams.open(7, f.expiry()));
        }
    }

    @Test void givenOwnedConnections__whenStoppingTwice__thenCompleteBeforeServerPhaseAndTerminateOwnedExecutorsOnce() throws Exception {
        var f = new Fixture(); f.streams.open(7, f.expiry()); var callbacks = new AtomicInteger();
        f.streams.stop(callbacks::incrementAndGet); f.streams.destroy();
        assertThat(f.streams.getPhase()).isEqualTo(Integer.MAX_VALUE);
        assertThat(f.streams.isRunning()).isFalse(); assertThat(callbacks).hasValue(1);
        assertThat(f.emitters.getFirst().completed).hasValue(1);
        assertThat(f.writes.shutdownNowCalls).isEqualTo(1); assertThat(f.completions.shutdownCalls).isEqualTo(1);
        verify(f.heartbeat).shutdownNow();
        assertThatThrownBy(() -> f.streams.open(7, f.expiry()))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode().value()).isEqualTo(503));
        assertThat(f.writes.awaitBudgets).allSatisfy(n -> assertThat(n).isBetween(0L, TimeUnit.SECONDS.toNanos(5)));
        assertThat(f.completions.awaitBudgets).allSatisfy(n -> assertThat(n).isBetween(0L, TimeUnit.SECONDS.toNanos(5)));
    }

    @Test void givenExecutorDoesNotTerminate__whenStopping__thenForceShutdownWithinSharedBudgetAndAlwaysNotifyLifecycle() throws Exception {
        var f = new Fixture(); f.writes.terminates = false; var callbacks = new AtomicInteger();
        f.streams.stop(callbacks::incrementAndGet);
        assertThat(callbacks).hasValue(1); assertThat(f.writes.shutdownNowCalls).isEqualTo(2);
        assertThat(f.writes.awaitBudgets).hasSize(1).allSatisfy(n -> assertThat(n).isBetween(0L, TimeUnit.SECONDS.toNanos(5)));
        assertThat(f.streams.isRunning()).isFalse(); f.close();
    }

    private static void assertFull(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
            assertThat(failure.getStatusCode().value()).isEqualTo(429);
            assertThat(failure.getHeaders().getFirst("Retry-After")).isEqualTo("15");
        });
    }

    private static final class Fixture implements AutoCloseable {
        final MutableClock clock = new MutableClock();
        final ManualExecutor writes = new ManualExecutor(), completions = new ManualExecutor();
        final ScheduledExecutorService heartbeat = mock(ScheduledExecutorService.class);
        final List<TrackingEmitter> emitters = new ArrayList<>();
        final PrivateNoteStreamRegistry streams;
        Fixture() throws Exception {
            when(heartbeat.awaitTermination(anyLong(), any())).thenReturn(true);
            streams = new PrivateNoteStreamRegistry(clock, timeout -> {
                var emitter = new TrackingEmitter(timeout); emitters.add(emitter); return emitter;
            }, writes, completions, heartbeat);
        }
        Instant expiry() { return clock.instant().plusSeconds(60); }
        @Override public void close() { streams.stop(); }
    }
    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2031-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
    }
    private static final class TrackingEmitter extends PrivateNoteStreamRegistry.StreamEmitter {
        final AtomicInteger sends = new AtomicInteger(), completed = new AtomicInteger();
        Runnable completion;
        Runnable timeout;
        Consumer<Throwable> error;
        TrackingEmitter(long timeout) { super(timeout); }
        @Override public void send(SseEventBuilder event) throws IOException { sends.incrementAndGet(); }
        @Override public void onCompletion(Runnable callback) { completion = callback; }
        @Override public void onTimeout(Runnable callback) { timeout = callback; }
        @Override public void onError(Consumer<Throwable> callback) { error = callback; }
        @Override public void complete() { completed.incrementAndGet(); completion.run(); }
        void lock() { writeLock.lock(); }
        void unlock() { writeLock.unlock(); }
        boolean lockOwned() { return ((java.util.concurrent.locks.ReentrantLock) writeLock).isHeldByCurrentThread(); }
        boolean writerWaiting() { return ((java.util.concurrent.locks.ReentrantLock) writeLock).hasQueuedThreads(); }
    }
    private static final class ManualExecutor extends AbstractExecutorService {
        final List<Runnable> tasks = new ArrayList<>();
        final List<Long> awaitBudgets = new ArrayList<>();
        int capacity = 1000, shutdownNowCalls, shutdownCalls;
        boolean stopped, rejectNext, terminates = true;
        @Override public void execute(Runnable task) {
            if (stopped || rejectNext || tasks.size() >= capacity) { rejectNext = false; throw new RejectedExecutionException(); }
            tasks.add(task);
        }
        void runAll() { while (!tasks.isEmpty()) tasks.removeFirst().run(); }
        @Override public void shutdown() { stopped = true; shutdownCalls++; }
        @Override public List<Runnable> shutdownNow() { stopped = true; shutdownNowCalls++; var old = List.copyOf(tasks); tasks.clear(); return old; }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped && tasks.isEmpty() && terminates; }
        @Override public boolean awaitTermination(long value, TimeUnit unit) { awaitBudgets.add(unit.toNanos(value)); if (terminates) runAll(); return isTerminated(); }
    }
}
