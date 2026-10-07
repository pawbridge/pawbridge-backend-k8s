package com.pawbridge.communityservice.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class PrivateNoteStreamTest {
    private final MutableClock clock = new MutableClock();
    private final ManualExecutor writes = new ManualExecutor();
    private final ManualExecutor completions = new ManualExecutor();
    private final List<TrackingEmitter> emitters = new ArrayList<>();
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private PrivateNoteStream stream = stream(writes, completions);

    private PrivateNoteStream stream(ExecutorService writeExecutor, ExecutorService completionExecutor) {
        return new PrivateNoteStream(clock, lifetime -> {
            TrackingEmitter emitter = new TrackingEmitter(lifetime);
            emitters.add(emitter);
            return emitter;
        }, writeExecutor, completionExecutor, scheduler);
    }

    @AfterEach
    void stop() {
        stream.shutdown();
        completions.runAll();
    }

    @Test
    void givenExpiredToken_whenOpening_thenRejectBeforeAllocatingEmitter() {
        assertStatus(() -> stream.open(7, clock.instant()), HttpStatus.UNAUTHORIZED);
        assertThat(emitters).isEmpty();
    }

    @Test
    void givenShortOrLongToken_whenOpening_thenLimitLifetimeToTokenOrFiveMinutes() {
        assertThat(stream.open(7, clock.instant().plusSeconds(12)).getTimeout()).isEqualTo(12_000);
        assertThat(stream.open(8, clock.instant().plusSeconds(600)).getTimeout()).isEqualTo(300_000);
        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
    }

    @Test
    void givenThreeActiveTabs_whenOpeningFourth_thenPreserveTabsAndAdviseRetry() {
        for (int index = 0; index < 3; index++) stream.open(7, expiry());

        assertThatThrownBy(() -> stream.open(7, expiry()))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                    assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(failure.getHeaders().getFirst("Retry-After")).isEqualTo("15");
                });
        assertThat(emitters).hasSize(3).allSatisfy(emitter -> {
            assertThat(emitter.sends.get()).isEqualTo(1);
            assertThat(emitter.completed.get()).isZero();
        });
    }

    @Test
    void givenFiveHundredConnections_whenAnotherMemberOpens_thenKeepGlobalCap() {
        for (long member = 1; member <= 500; member++) stream.open(member, expiry());

        assertStatus(() -> stream.open(501, expiry()), HttpStatus.TOO_MANY_REQUESTS);
        assertThat(emitters).hasSize(500);
    }

    @Test
    void givenPendingClose_whenNewConnectionsRegister_thenAllRemainTrackedAndLateCallbackIsHarmless() {
        stream.open(7, expiry());
        TrackingEmitter old = emitters.get(0);
        stream.close(7);
        stream.open(7, expiry());
        stream.open(7, expiry());
        assertStatus(() -> stream.open(7, expiry()), HttpStatus.TOO_MANY_REQUESTS);

        completions.runAll();
        stream.open(7, expiry());
        old.completion.run();

        assertStatus(() -> stream.open(7, expiry()), HttpStatus.TOO_MANY_REQUESTS);
        assertThat(old.completed.get()).isEqualTo(1);
        assertThat(emitters.subList(1, 4)).allSatisfy(emitter -> assertThat(emitter.completed.get()).isZero());
    }

    @Test
    void givenQueuedNotification_whenTokenExpiresBeforeWrite_thenDoNotSendPayload() {
        stream.open(7, clock.instant().plusSeconds(1));
        stream.publish(7, null);
        clock.advance(2);

        writes.runAll();
        assertThat(emitters.get(0).sends.get()).isEqualTo(1);
        completions.runAll();
        assertThat(emitters.get(0).completed.get()).isEqualTo(1);
    }

    @Test
    void givenSpringWriteLockHeld_whenWaitingWriterExpires_thenDoNotSendPayload() throws Exception {
        stream.open(7, clock.instant().plusSeconds(1));
        TrackingEmitter emitter = emitters.get(0);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch queued = new CountDownLatch(1);
        emitter.writeLockHeld();
        try {
            stream.publish(7, null);
            var pending = worker.submit(() -> {
                queued.countDown();
                writes.runAll();
            });
            assertThat(queued.await(1, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!emitter.writerWaiting() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(emitter.writerWaiting()).isTrue();
            clock.advance(2);
            emitter.releaseWriteLock();
            pending.get(1, TimeUnit.SECONDS);
            assertThat(emitter.sends.get()).isEqualTo(1);
            completions.runAll();
            assertThat(emitter.completed.get()).isEqualTo(1);
        } finally {
            if (emitter.writeLockOwned()) emitter.releaseWriteLock();
            worker.shutdownNow();
        }
    }

    @Test
    void givenRejectedCompletion_whenHeartbeatRetries_thenKeepSlotCountedAndSuppressNewWrites() {
        stream.open(7, clock.instant().plusSeconds(1));
        clock.advance(2);
        completions.rejectNext = true;
        stream.heartbeat();
        stream.open(7, expiry());
        stream.open(7, expiry());
        assertStatus(() -> stream.open(7, expiry()), HttpStatus.TOO_MANY_REQUESTS);
        stream.publish(7, null);
        writes.runAll();
        assertThat(emitters.get(0).sends.get()).isEqualTo(1);

        stream.heartbeat();
        completions.runAll();
        stream.open(7, expiry());
        assertThat(emitters.get(0).completed.get()).isEqualTo(1);
    }

    @Test
    void givenWriteFailure_whenSending_thenReleaseSlotWithoutCompletingFailedWriteAgain() {
        stream.open(7, expiry());
        TrackingEmitter failed = emitters.get(0);
        failed.failWrite = true;
        stream.publish(7, null);
        writes.runAll();

        for (int index = 0; index < 3; index++) stream.open(7, expiry());
        failed.completion.run();
        assertStatus(() -> stream.open(7, expiry()), HttpStatus.TOO_MANY_REQUESTS);
        assertThat(failed.completed.get()).isZero();
    }

    @Test
    void givenClosingConnection_whenNotificationWasAlreadyQueued_thenSuppressItsNewWrite() {
        stream.open(7, expiry());
        stream.publish(7, null);
        stream.close(7);

        writes.runAll();
        assertThat(emitters.get(0).sends.get()).isEqualTo(1);
        completions.runAll();
        assertThat(emitters.get(0).completed.get()).isEqualTo(1);
    }

    @Test
    void givenSlowCompletion_whenHeartbeatAndAnotherMemberOpen_thenNeitherWaitsForNetworkCompletion() throws Exception {
        stream.shutdown();
        ExecutorService closeWorkers = Executors.newFixedThreadPool(2);
        ExecutorService requests = Executors.newSingleThreadExecutor();
        ManualExecutor activeWrites = new ManualExecutor();
        stream = stream(activeWrites, closeWorkers);
        stream.open(7, clock.instant().plusSeconds(1));
        TrackingEmitter slow = emitters.get(0);
        slow.completeEntered = new CountDownLatch(1);
        slow.allowComplete = new CountDownLatch(1);
        clock.advance(2);
        try {
            requests.submit(stream::heartbeat).get(1, TimeUnit.SECONDS);
            assertThat(slow.completeEntered.await(1, TimeUnit.SECONDS)).isTrue();
            requests.submit(() -> stream.open(8, expiry())).get(1, TimeUnit.SECONDS);
            requests.submit(stream::heartbeat).get(1, TimeUnit.SECONDS);
            assertThat(emitters).hasSize(2);
            activeWrites.runAll();
            assertThat(emitters.get(1).sends.get()).isEqualTo(2);
        } finally {
            slow.allowComplete.countDown();
            closeWorkers.shutdownNow();
            requests.shutdownNow();
        }
    }

    @Test
    void givenConcurrentOpens_whenSameMemberConnects_thenExactlyThreeAreAdmitted() throws Exception {
        ExecutorService requests = Executors.newFixedThreadPool(10);
        CyclicBarrier start = new CyclicBarrier(10);
        try {
            var attempts = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 0; index < 10; index++) attempts.add(requests.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                try {
                    stream.open(7, expiry());
                    return true;
                } catch (ResponseStatusException full) {
                    assertThat(full.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    return false;
                }
            }));
            int admitted = 0;
            for (var attempt : attempts) if (attempt.get(3, TimeUnit.SECONDS)) admitted++;
            assertThat(admitted).isEqualTo(3);
        } finally {
            requests.shutdownNow();
        }
    }

    @Test
    void givenStoppedStream_whenOpening_thenRejectNewRegistration() {
        stream.shutdown();
        assertStatus(() -> stream.open(7, expiry()), HttpStatus.SERVICE_UNAVAILABLE);
    }

    private Instant expiry() { return clock.instant().plusSeconds(60); }

    private void assertStatus(Runnable request, HttpStatus expected) {
        assertThatThrownBy(request::run).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(expected));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-07T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class TrackingEmitter extends PrivateNoteStream.StreamEmitter {
        private final AtomicInteger sends = new AtomicInteger();
        private final AtomicInteger completed = new AtomicInteger();
        private Runnable completion = () -> {};
        private boolean failWrite;
        private CountDownLatch completeEntered;
        private CountDownLatch allowComplete;

        private TrackingEmitter(long lifetime) { super(lifetime); }
        void writeLockHeld() { writeLock.lock(); }
        void releaseWriteLock() { writeLock.unlock(); }
        boolean writerWaiting() { return ((java.util.concurrent.locks.ReentrantLock) writeLock).hasQueuedThreads(); }
        boolean writeLockOwned() { return ((java.util.concurrent.locks.ReentrantLock) writeLock).isHeldByCurrentThread(); }
        @Override public void onCompletion(Runnable callback) { completion = callback; }
        @Override public void send(SseEventBuilder event) throws IOException {
            if (failWrite) throw new IOException("synthetic disconnected writer");
            sends.incrementAndGet();
        }
        @Override public void complete() {
            if (completeEntered != null) completeEntered.countDown();
            if (allowComplete != null) {
                try { allowComplete.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            completed.incrementAndGet();
            completion.run();
        }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean stopped;
        private boolean rejectNext;
        void runAll() { while (!tasks.isEmpty()) tasks.removeFirst().run(); }
        @Override public void execute(Runnable task) {
            if (stopped || rejectNext) {
                rejectNext = false;
                throw new RejectedExecutionException();
            }
            tasks.addLast(task);
        }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; return List.of(); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
