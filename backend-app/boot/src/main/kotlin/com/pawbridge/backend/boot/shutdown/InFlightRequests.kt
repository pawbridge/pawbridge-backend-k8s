package com.pawbridge.backend.boot.shutdown

import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Requests the public server has received and not finished, kept only so a slow graceful shutdown can say what
 * it is waiting for. A synchronous request leaves when its filter chain returns; an asynchronous one (SSE) leaves
 * when the container reports completion, so an async request the container never completes stays visible.
 *
 * Only the method, the path without query and timestamps are kept. The number of entries is bounded; requests
 * beyond the bound are counted, not tracked.
 */
class InFlightRequests(
    private val clock: Clock,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    class Entry(val method: String, val path: String, val startedAt: Instant) {
        @Volatile
        var async: Boolean = false

        @Volatile
        var timedOutAt: Instant? = null

        @Volatile
        var failedAt: Instant? = null
    }

    data class Snapshot(val now: Instant, val entries: List<Entry>, val untracked: Long)

    private val entries = ConcurrentHashMap<Long, Entry>()
    private val ids = AtomicLong()
    private val untracked = AtomicLong()

    init {
        if (capacity <= 0) {
            throw IllegalArgumentException("In-flight request capacity must be positive")
        }
    }

    fun snapshot(): Snapshot = Snapshot(clock.instant(), entries.values.sortedBy { it.startedAt }, untracked.get())

    fun filter(): OncePerRequestFilter = Tracking()

    // Async and error dispatches of a tracked request are skipped (OncePerRequestFilter defaults), so each
    // request is registered once, on its first dispatch.
    private inner class Tracking : OncePerRequestFilter() {
        @Throws(ServletException::class, IOException::class)
        override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
            if (entries.size >= capacity) {
                untracked.incrementAndGet()
                filterChain.doFilter(request, response)
                return
            }
            val id = ids.incrementAndGet()
            val entry = Entry(request.method, request.requestURI, clock.instant())
            entries[id] = entry
            try {
                filterChain.doFilter(request, response)
            } finally {
                if (request.isAsyncStarted) {
                    entry.async = true
                    // Completion is delivered after this dispatch returns, so the listener cannot miss it.
                    request.asyncContext.addListener(Completion(id, entry))
                } else {
                    entries.remove(id)
                }
            }
        }
    }

    private inner class Completion(private val id: Long, private val entry: Entry) : AsyncListener {
        override fun onComplete(event: AsyncEvent) {
            entries.remove(id)
        }

        override fun onTimeout(event: AsyncEvent) {
            entry.timedOutAt = clock.instant()
        }

        override fun onError(event: AsyncEvent) {
            entry.failedAt = clock.instant()
        }

        override fun onStartAsync(event: AsyncEvent) {
            // A new async cycle drops the previous listeners; stay registered for its completion.
            event.asyncContext.addListener(this)
        }
    }

    private companion object {
        private const val DEFAULT_CAPACITY = 1_000
    }
}
