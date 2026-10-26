package com.pawbridge.backend.boot.security

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.ArrayDeque
import java.util.Collections
import java.util.Enumeration
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeoutException
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.UrlPathHelper

/**
 * Bounded public upload admission for `POST /api/v1/animals/lost-candidates`, moved from the API Gateway
 * `LostSearchLimits` filter. Counters apply to this application instance, not to individual clients.
 *
 * Differences from the Gateway filter: the body is not buffered here (the servlet container parses the multipart
 * parts itself), so the size cap is the declared Content-Length and requests without one are refused with 411;
 * permits are taken only after the body has been received so slow uploads cannot hold them;
 * the 50 second limit is not enforced by interrupting the request, only by the 3s+45s Python client timeouts and by
 * mapping a timeout that escapes the controller to 504.
 */
class LostSearchLimitsFilter(
    maxBytes: Int,
    maxConcurrent: Int,
    requestsPerMinute: Int,
    timeoutSeconds: Int,
) : OncePerRequestFilter() {
    private val maxBytes: Int
    private val requestsPerMinute: Int
    private val active: Semaphore
    private val starts = ArrayDeque<Long>()

    init {
        if (maxBytes <= 0 || maxConcurrent <= 0 || requestsPerMinute <= 0 || timeoutSeconds <= 0) {
            throw IllegalArgumentException("Lost search limits must be positive")
        }
        this.maxBytes = maxBytes
        this.active = Semaphore(maxConcurrent)
        this.requestsPerMinute = requestsPerMinute
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        // Decide on the decoded path (what Tomcat mapping, Security and MVC use), never the raw URI:
        // /lost-%63andidates must not slip past the limits.
        val path = UrlPathHelper.defaultInstance.getPathWithinApplication(request)
        return "POST" != request.method || !(PATH == path || (PATH + "/") == path)
    }

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val length = request.contentLengthLong
        if (length > maxBytes) {
            reject(response, 413)
            return
        }
        val type: MediaType?
        try {
            val contentType = request.contentType
            type = if (contentType == null) null else MediaType.parseMediaType(contentType)
        } catch (ex: IllegalArgumentException) {
            reject(response, 415)
            return
        }
        if (type == null || !MediaType.MULTIPART_FORM_DATA.isCompatibleWith(type)) {
            reject(response, 415)
            return
        }
        if (length < 0) {
            reject(response, 411)
            return
        }
        // Read the whole multipart body before taking a permit: a slow or stalled upload then occupies only its own
        // request thread, so two slow clients cannot exhaust the concurrency permits for everyone. A malformed body is
        // left for the handler to report exactly as before (the container rethrows the same failure there).
        try {
            request.parts
        } catch (ignored: IOException) {
            // Reported by the handler.
        } catch (ignored: ServletException) {
            // Reported by the handler.
        } catch (ignored: IllegalStateException) {
            // Reported by the handler.
        }
        if (!active.tryAcquire()) {
            reject(response, 503)
            return
        }
        try {
            if (!admit()) {
                reject(response, 429)
                return
            }
            if (length == 0L) {
                reject(response, 400)
                return
            }
            try {
                filterChain.doFilter(StrippedRequest(request), response)
            } catch (ex: ServletException) {
                rejectTimeoutOrRethrow(response, ex)
            } catch (ex: RuntimeException) {
                rejectTimeoutOrRethrow(response, ex)
            }
        } finally {
            active.release()
        }
    }

    private fun rejectTimeoutOrRethrow(response: HttpServletResponse, failure: Exception) {
        if (response.isCommitted || !timedOut(failure)) {
            throw failure
        }
        reject(response, 504)
    }

    @Synchronized
    private fun admit(): Boolean {
        val now = System.nanoTime()
        val minute = Duration.ofMinutes(1).toNanos()
        while (!starts.isEmpty() && now - starts.peekFirst() >= minute) {
            starts.removeFirst()
        }
        if (starts.size >= requestsPerMinute) {
            return false
        }
        starts.addLast(now)
        return true
    }

    /** Client-supplied identity and internal-key headers must never reach the downstream handler. */
    private class StrippedRequest(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
        override fun getHeader(name: String?): String? {
            return if (hidden(name)) null else super.getHeader(name)
        }

        override fun getHeaders(name: String?): Enumeration<String> {
            return if (hidden(name)) Collections.emptyEnumeration() else super.getHeaders(name)
        }

        override fun getHeaderNames(): Enumeration<String> {
            val names = Collections.list(super.getHeaderNames()).filter { name -> !hidden(name) }
            return Collections.enumeration(names)
        }

        private fun hidden(name: String?): Boolean {
            return name != null && STRIPPED_HEADERS.contains(name.lowercase(Locale.ROOT))
        }
    }

    companion object {
        const val PATH: String = "/api/v1/animals/lost-candidates"
        private val STRIPPED_HEADERS = setOf("authorization", "x-user-id", "x-internal-api-key", "transfer-encoding")

        private fun timedOut(error: Throwable): Boolean {
            var cause: Throwable? = error
            while (cause != null) {
                if (cause is TimeoutException || cause is SocketTimeoutException) {
                    return true
                }
                cause = if (cause.cause === cause) null else cause.cause
            }
            return false
        }

        @Throws(IOException::class)
        private fun reject(response: HttpServletResponse, status: Int) {
            if (response.isCommitted) {
                return
            }
            response.resetBuffer()
            response.status = status
            response.contentType = "application/json"
            if (status == 429) {
                response.setHeader("Retry-After", "60")
            }
            if (status == 503) {
                response.setHeader("Retry-After", "3")
            }
            val message = when (status) {
                413 -> "사진 크기 제한을 초과했습니다"
                429 -> "검색 요청이 많습니다. 잠시 후 다시 시도해 주세요"
                504 -> "검색 시간이 초과됐습니다"
                415, 411 -> "사진 업로드 형식을 확인해 주세요"
                400 -> "사진을 선택해 주세요"
                else -> "잠시 후 다시 시도해 주세요"
            }
            val body = ("{\"message\":\"" + message + "\"}").toByteArray(StandardCharsets.UTF_8)
            response.setContentLength(body.size)
            response.outputStream.write(body)
        }
    }
}
