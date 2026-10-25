package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.NoteNotifications
import com.pawbridge.backend.community.internal.note.application.RequireActiveNoteStream
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import java.util.function.LongFunction

/** Manual executors/emitters verify registry policy; they do not model real sockets or servlet shutdown. */
class PrivateNoteStreamRegistryTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun givenWithdrawnMemberTabsOrNoMemberTabsAndAnotherMember__whenClosingOnlyThatMemberThenResyncingAll__thenCompleteOnlyOwnedTabsAndKeepTheRegistryRunning(
        hasOwner: Boolean,
    ) {
        Fixture().use { f ->
            if (hasOwner) {
                f.streams.open(7, f.expiry())
                f.streams.open(7, f.expiry())
            }
            f.streams.open(8, f.expiry())
            val other = if (hasOwner) 2 else 0
            f.streams.closeMember(7)
            f.streams.resyncAll()
            f.writes.runAll()
            f.completions.runAll()
            for (i in 0 until other) {
                assertThat(f.emitters[i].completed).hasValue(1)
                assertThat(f.emitters[i].sends).hasValue(1)
            }
            assertThat(f.emitters[other].completed).hasValue(0)
            assertThat(f.emitters[other].frames).hasSize(2)
            assertThat(f.emitters[other].frames.last().filter { it is String }.map { it.toString() })
                .anySatisfy({ part -> assertThat(part).contains("event:resync\n") })
            assertThat(f.streams.isRunning()).isTrue()
            f.streams.open(9, f.expiry())
            f.streams.resync(8)
            f.writes.runAll()
            assertThat(f.emitters[other].sends).hasValue(3)
        }
    }

    @Test
    fun givenFullCompletionQueueDuringMemberRemoval__whenTheExistingHeartbeatRetries__thenKeepTheClosingTabCountedAndEventuallyCloseItWithoutSendingItHints() {
        Fixture().use { f ->
            f.completions.capacity = 1
            f.streams.open(7, f.expiry())
            f.streams.open(7, f.expiry())
            f.streams.open(8, f.expiry())
            f.streams.closeMember(7)
            f.streams.resyncAll()
            f.writes.runAll()
            assertThat(f.emitters[0].sends).hasValue(1)
            assertThat(f.emitters[1].sends).hasValue(1)
            f.completions.runAll()
            f.streams.heartbeat()
            f.completions.runAll()
            assertThat(f.emitters[0].completed).hasValue(1)
            assertThat(f.emitters[1].completed).hasValue(1)
            assertThat(f.emitters[2].completed).hasValue(0)
            assertThat(f.streams.isRunning()).isTrue()
        }
    }

    @Test
    fun givenRecipientTabsAndAnotherMember__whenPublishingCommittedNote__thenUseOnlyTheSameBoundedRecipientRegistryAndExactPayload() {
        Fixture().use { f ->
            f.streams.open(7, f.expiry())
            f.streams.open(7, f.expiry())
            f.streams.open(8, f.expiry())
            val payload = notification(f.clock.instant())
            f.streams.publish(7, payload)
            assertThat(f.writes.tasks).hasSize(2)
            assertThat(f.emitters).allSatisfy({ emitter -> assertThat(emitter.sends).hasValue(1) })
            f.writes.runAll()
            for (index in intArrayOf(0, 1)) {
                val frames = f.emitters[index].frames
                assertThat(frames).hasSize(2)
                assertThat(frames.last()).contains(payload)
                assertThat(frames.last().filter { it is String }.map { it.toString() })
                    .anySatisfy({ part -> assertThat(part).contains("event:note\n") })
            }
            assertThat(f.emitters[2].frames).hasSize(1)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["expired", "full"])
    fun givenExpiredConnectionOrFullWriteQueue__whenPublishingNote__thenKeepExistingBoundedBestEffortWithoutLateDelivery(
        condition: String,
    ) {
        Fixture().use { f ->
            f.streams.open(7, f.clock.instant().plusSeconds(if (condition == "expired") 1 else 60))
            val payload = notification(f.clock.instant())
            f.writes.capacity = 1
            f.streams.publish(7, payload)
            if (condition == "expired") {
                f.clock.advance(2)
            } else {
                assertThatCode { f.streams.publish(7, payload) }.doesNotThrowAnyException()
            }
            assertThat(f.writes.tasks).hasSize(1)
            f.writes.runAll()
            f.completions.runAll()
            assertThat(f.emitters.first().sends).hasValue(if (condition == "expired") 1 else 2)
            assertThat(f.emitters.first().completed).hasValue(if (condition == "expired") 1 else 0)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["member", "global", "pending"])
    fun givenConnectionBudget__whenOpeningBeyondIt__thenReturn429WithRetryAfterAndKeepExistingConnections(
        budget: String,
    ) {
        Fixture().use { f ->
            if (budget == "member") {
                for (n in 0 until 3) {
                    f.streams.open(7, f.expiry())
                }
            } else if (budget == "global") {
                for (id in 1L..500L) {
                    f.streams.open(id, f.expiry())
                }
            } else {
                val client = UUID.randomUUID()
                for (n in 0 until 4) {
                    f.streams.open(7, f.expiry(), client)
                }
                assertFull { f.streams.open(7, f.expiry(), client) }
                assertThat(f.emitters).hasSize(4)
                return@use
            }
            assertFull { f.streams.open(if (budget == "global") 501 else 7, f.expiry()) }
            assertThat(f.emitters).hasSize(if (budget == "global") 500 else 3)
            assertThat(f.emitters).allSatisfy({ emitter -> assertThat(emitter.completed.get()).isZero() })
        }
    }

    @Test
    fun givenSameClientReconnectAndLateOldCallback__whenResyncing__thenPreserveReplacementOtherTabsAndOtherMembers() {
        Fixture().use { f ->
            val tab = UUID.randomUUID()
            f.streams.open(7, f.expiry(), tab)
            val old = f.emitters.first()
            f.streams.open(8, f.expiry(), tab)
            f.streams.open(7, f.expiry(), UUID.randomUUID())
            f.streams.open(7, f.expiry(), tab)
            f.completions.runAll()
            old.completion.run()
            f.streams.resync(7)
            f.writes.runAll()
            assertThat(old.completed).hasValue(1)
            assertThat(old.sends).hasValue(1)
            assertThat(f.emitters[1].sends).hasValue(1)
            assertThat(f.emitters[2].sends).hasValue(2)
            assertThat(f.emitters[3].sends).hasValue(2)
            f.streams.open(7, f.expiry(), UUID.randomUUID())
            assertFull { f.streams.open(7, f.expiry()) }
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [12, 600])
    fun givenVerifiedJwtLifetime__whenOpening__thenUseTokenOrFiveMinuteCapAndOneFifteenSecondHeartbeat(seconds: Long) {
        Fixture().use { f ->
            assertThat(f.streams.open(7, f.clock.instant().plusSeconds(seconds)).timeout)
                .isEqualTo(Math.min(seconds, 300) * 1000)
            verify(f.heartbeat).scheduleAtFixedRate(
                any(Runnable::class.java),
                eq(15L),
                eq(15L),
                eq(TimeUnit.SECONDS),
            )
        }
    }

    @Test
    fun givenMemberReadCrossesVerifiedExpiry__whenOpening__thenFinishMemberQueryButAdmitNoEmitter() {
        Fixture().use { f ->
            val expiry = f.clock.instant().plusSeconds(1)
            val members = mock(ContactMemberQuery::class.java)
            whenever(members.get(7)).thenAnswer {
                f.clock.advance(2)
                ContactMember(7L, "", true, false)
            }
            val authentication = UsernamePasswordAuthenticationToken(7L, null, emptyList<GrantedAuthority>())
            authentication.details = expiry
            val controller = NoteStreamController(RequireActiveNoteStream(members), f.streams)
            assertThatThrownBy { controller.open(7L, authentication, null) }
                .isInstanceOfSatisfying(ResponseStatusException::class.java) { failure ->
                    assertThat(failure.statusCode.value()).isEqualTo(401)
                }
            verify(members).get(7)
            verifyNoMoreInteractions(members)
            assertThat(f.emitters).isEmpty()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun givenQueuedHintOrHeldSpringWriteLock__whenTokenExpiresBeforeActualSend__thenSuppressResyncAndCompleteConnection(
        locked: Boolean,
    ) {
        Fixture().use { f ->
            f.streams.open(7, f.clock.instant().plusSeconds(1))
            val emitter = f.emitters.first()
            f.streams.resync(7)
            if (locked) {
                val worker = Executors.newSingleThreadExecutor()
                emitter.lock()
                try {
                    val task = worker.submit(Runnable { f.writes.runAll() })
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                    while (!emitter.writerWaiting() && System.nanoTime() < deadline) {
                        Thread.onSpinWait()
                    }
                    assertThat(emitter.writerWaiting()).isTrue()
                    f.clock.advance(2)
                    emitter.unlock()
                    task.get(1, TimeUnit.SECONDS)
                } finally {
                    if (emitter.lockOwned()) {
                        emitter.unlock()
                    }
                    worker.shutdownNow()
                    assertThat(worker.awaitTermination(1, TimeUnit.SECONDS)).isTrue()
                }
            } else {
                f.clock.advance(2)
                f.writes.runAll()
            }
            f.completions.runAll()
            assertThat(emitter.sends).hasValue(1)
            assertThat(emitter.completed).hasValue(1)
        }
    }

    @Test
    fun givenRejectedCompletion__whenHeartbeatRetries__thenKeepClosingSlotCountedAndSuppressAlreadyQueuedHint() {
        Fixture().use { f ->
            f.streams.open(7, f.clock.instant().plusSeconds(1))
            f.streams.resync(7)
            f.clock.advance(2)
            f.completions.rejectNext = true
            f.streams.heartbeat()
            f.streams.open(7, f.expiry())
            f.streams.open(7, f.expiry())
            assertFull { f.streams.open(7, f.expiry()) }
            f.writes.runAll()
            assertThat(f.emitters.first().sends).hasValue(1)
            f.streams.heartbeat()
            f.completions.runAll()
            f.streams.open(7, f.expiry())
            assertThat(f.emitters.first().completed).hasValue(1)
        }
    }

    @Test
    fun givenFullWriteQueue__whenAnotherHintArrives__thenDropOnlyHintWithoutUnboundedQueueOrThrowing() {
        Fixture().use { f ->
            f.writes.capacity = 1
            f.streams.open(7, f.expiry())
            f.streams.resync(7)
            assertThatCode { f.streams.resync(7) }.doesNotThrowAnyException()
            assertThat(f.writes.tasks).hasSize(1)
            f.writes.runAll()
            assertThat(f.emitters.first().sends).hasValue(2)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["error", "timeout"])
    fun givenDisconnectOrTimeoutCallback__whenCleaningConnection__thenReleaseOnlyItsSlotAndSuppressQueuedWrites(
        callback: String,
    ) {
        Fixture().use { f ->
            for (n in 0 until 3) {
                f.streams.open(7, f.expiry())
            }
            val closing = f.emitters.first()
            f.streams.resync(7)
            if (callback == "error") {
                closing.error.accept(IOException("synthetic-disconnect"))
            } else {
                closing.timeout.run()
                assertFull { f.streams.open(7, f.expiry()) }
                f.completions.runAll()
            }
            f.streams.open(7, f.expiry())
            f.writes.runAll()
            assertThat(closing.sends).hasValue(1)
            assertThat(closing.completed).hasValue(if (callback == "error") 0 else 1)
            assertFull { f.streams.open(7, f.expiry()) }
        }
    }

    @Test
    fun givenOwnedConnections__whenStoppingTwice__thenCompleteBeforeServerPhaseAndTerminateOwnedExecutorsOnce() {
        val f = Fixture()
        f.streams.open(7, f.expiry())
        val callbacks = AtomicInteger()
        f.streams.stop(Runnable { callbacks.incrementAndGet() })
        f.streams.destroy()
        assertThat(f.streams.getPhase()).isEqualTo(Integer.MAX_VALUE)
        assertThat(f.streams.isRunning()).isFalse()
        assertThat(callbacks).hasValue(1)
        assertThat(f.emitters.first().completed).hasValue(1)
        assertThat(f.writes.shutdownNowCalls).isEqualTo(1)
        assertThat(f.completions.shutdownCalls).isEqualTo(1)
        verify(f.heartbeat).shutdownNow()
        assertThatThrownBy { f.streams.open(7, f.expiry()) }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) { failure ->
                assertThat(failure.statusCode.value()).isEqualTo(503)
            }
        assertThat(f.writes.awaitBudgets)
            .allSatisfy({ n -> assertThat(n).isBetween(0L, TimeUnit.SECONDS.toNanos(5)) })
        assertThat(f.completions.awaitBudgets)
            .allSatisfy({ n -> assertThat(n).isBetween(0L, TimeUnit.SECONDS.toNanos(5)) })
    }

    @Test
    fun givenExecutorDoesNotTerminate__whenStopping__thenForceShutdownWithinSharedBudgetAndAlwaysNotifyLifecycle() {
        val f = Fixture()
        f.writes.terminates = false
        val callbacks = AtomicInteger()
        f.streams.stop(Runnable { callbacks.incrementAndGet() })
        assertThat(callbacks).hasValue(1)
        assertThat(f.writes.shutdownNowCalls).isEqualTo(2)
        assertThat(f.writes.awaitBudgets).hasSize(1)
            .allSatisfy({ n -> assertThat(n).isBetween(0L, TimeUnit.SECONDS.toNanos(5)) })
        assertThat(f.streams.isRunning()).isFalse()
        f.close()
    }

    private fun assertFull(action: () -> Unit) {
        assertThatThrownBy { action() }.isInstanceOfSatisfying(ResponseStatusException::class.java) { failure ->
            assertThat(failure.statusCode.value()).isEqualTo(429)
            assertThat(failure.headers.getFirst("Retry-After")).isEqualTo("15")
        }
    }

    private fun notification(now: Instant): NoteNotifications.Notification =
        NoteNotifications.Notification(
            UUID.fromString("f2900000-0000-0000-0000-000000000001"),
            "PRIVATE_NOTE",
            8L,
            " raw nickname ",
            now,
            false,
            "/notes/f2900000-0000-0000-0000-000000000001",
        )

    private class Fixture : AutoCloseable {
        val clock = MutableClock()
        val writes = ManualExecutor()
        val completions = ManualExecutor()
        val heartbeat: ScheduledExecutorService = mock(ScheduledExecutorService::class.java)
        val emitters = ArrayList<TrackingEmitter>()
        val streams: PrivateNoteStreamRegistry

        init {
            whenever(heartbeat.awaitTermination(anyLong(), any(TimeUnit::class.java))).thenReturn(true)
            streams = PrivateNoteStreamRegistry(
                clock,
                LongFunction { timeout ->
                    val emitter = TrackingEmitter(timeout)
                    emitters.add(emitter)
                    emitter
                },
                writes,
                completions,
                heartbeat,
            )
        }

        fun expiry(): Instant = clock.instant().plusSeconds(60)

        override fun close() {
            streams.stop()
        }
    }

    private class MutableClock : Clock() {
        @Volatile
        private var now: Instant = Instant.parse("2031-01-01T00:00:00Z")

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now

        fun advance(seconds: Long) {
            now = now.plusSeconds(seconds)
        }
    }

    private class TrackingEmitter(timeout: Long) : PrivateNoteStreamRegistry.StreamEmitter(timeout) {
        val sends = AtomicInteger()
        val completed = AtomicInteger()
        val frames = ArrayList<List<Any?>>()
        lateinit var completion: Runnable
        lateinit var timeout: Runnable
        lateinit var error: Consumer<Throwable>

        @Throws(IOException::class)
        override fun send(event: SseEmitter.SseEventBuilder) {
            frames.add(event.build().map { part -> part.data })
            sends.incrementAndGet()
        }

        override fun onCompletion(callback: Runnable) {
            completion = callback
        }

        override fun onTimeout(callback: Runnable) {
            timeout = callback
        }

        override fun onError(callback: Consumer<Throwable>) {
            error = callback
        }

        override fun complete() {
            completed.incrementAndGet()
            completion.run()
        }

        fun lock() {
            writeLock.lock()
        }

        fun unlock() {
            writeLock.unlock()
        }

        fun lockOwned(): Boolean = (writeLock as ReentrantLock).isHeldByCurrentThread

        fun writerWaiting(): Boolean = (writeLock as ReentrantLock).hasQueuedThreads()
    }

    private class ManualExecutor : AbstractExecutorService() {
        val tasks = ArrayList<Runnable>()
        val awaitBudgets = ArrayList<Long>()
        var capacity = 1000
        var shutdownNowCalls = 0
        var shutdownCalls = 0
        var stopped = false
        var rejectNext = false
        var terminates = true

        override fun execute(task: Runnable) {
            if (stopped || rejectNext || tasks.size >= capacity) {
                rejectNext = false
                throw RejectedExecutionException()
            }
            tasks.add(task)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) {
                tasks.removeAt(0).run()
            }
        }

        override fun shutdown() {
            stopped = true
            shutdownCalls++
        }

        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            shutdownNowCalls++
            val old = ArrayList(tasks)
            tasks.clear()
            return old
        }

        override fun isShutdown(): Boolean = stopped

        override fun isTerminated(): Boolean = stopped && tasks.isEmpty() && terminates

        override fun awaitTermination(value: Long, unit: TimeUnit): Boolean {
            awaitBudgets.add(unit.toNanos(value))
            if (terminates) {
                runAll()
            }
            return isTerminated
        }
    }
}
