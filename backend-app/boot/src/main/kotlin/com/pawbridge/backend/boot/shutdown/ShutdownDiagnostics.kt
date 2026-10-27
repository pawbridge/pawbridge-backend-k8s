package com.pawbridge.backend.boot.shutdown

import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle
import org.springframework.context.SmartLifecycle

/**
 * Logs what the web server's graceful shutdown will wait for, right before it starts, and again every [interval]
 * while anything is left. It never delays shutdown: the follow-up reports run on a daemon thread.
 *
 * [containerAsync] is the servlet container's own count of unfinished async requests (null when unknown). It is
 * what graceful shutdown actually waits on, so it is reported next to the tracked requests: a count above the
 * tracked async requests means the container holds a request this application no longer sees.
 */
class ShutdownDiagnostics(
    private val requests: InFlightRequests,
    private val containerAsync: () -> Long?,
    private val interval: Duration = Duration.ofSeconds(10),
    private val maxReports: Int = 60,
    private val report: (String) -> Unit = { message -> log.info(message) },
    private val background: (Runnable) -> Unit = { work -> daemonThread(work).start() },
    private val pause: (Duration) -> Unit = { duration -> Thread.sleep(duration.toMillis()) },
) : SmartLifecycle {

    private data class State(val busy: Boolean, val message: String)

    @Volatile
    private var running = false

    override fun start() {
        running = true
    }

    override fun stop() {
        stop {}
    }

    override fun stop(callback: Runnable) {
        running = false
        try {
            val first = describe()
            report(first.message)
            if (first.busy) {
                background(Runnable { follow() })
            }
        } finally {
            callback.run()
        }
    }

    override fun isRunning(): Boolean = running

    // Stops just before the web server's graceful shutdown phase, after the note streams have been closed.
    override fun getPhase(): Int = WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE + 1

    private fun follow() {
        try {
            repeat(maxReports) {
                pause(interval)
                val state = describe()
                if (!state.busy) {
                    report("Graceful shutdown requests drained")
                    return
                }
                report(state.message)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun describe(): State {
        val snapshot = requests.snapshot()
        val async = try {
            containerAsync()
        } catch (unavailable: RuntimeException) {
            null
        }
        val busy = snapshot.entries.isNotEmpty() || (async ?: 0) > 0
        val shown = snapshot.entries.take(MAX_LISTED).joinToString("; ") { entry -> describe(entry, snapshot.now) }
        val message = "Graceful shutdown waits for ${snapshot.entries.size} tracked request(s), " +
            "container async in progress=${async ?: "unknown"}, untracked=${snapshot.untracked}" +
            if (shown.isEmpty()) "" else ": $shown"
        return State(busy, message)
    }

    private fun describe(entry: InFlightRequests.Entry, now: Instant): String {
        val parts = mutableListOf("${entry.method} ${entry.path}", "age=${seconds(entry.startedAt, now)}s")
        if (entry.async) {
            parts.add("async")
        }
        entry.timedOutAt?.let { parts.add("timedOut=${seconds(it, now)}s ago") }
        entry.failedAt?.let { parts.add("failed=${seconds(it, now)}s ago") }
        return parts.joinToString(" ")
    }

    private fun seconds(from: Instant, to: Instant): Long = Duration.between(from, to).seconds

    private companion object {
        private val log = LoggerFactory.getLogger(ShutdownDiagnostics::class.java)
        private const val MAX_LISTED = 20

        private fun daemonThread(work: Runnable): Thread {
            val thread = Thread(work, "shutdown-diagnostics")
            thread.isDaemon = true
            return thread
        }
    }
}
