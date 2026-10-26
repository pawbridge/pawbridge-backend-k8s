package com.pawbridge.backend.community.internal.visit.web

import com.pawbridge.backend.community.internal.visit.application.VisitCollection
import com.pawbridge.backend.community.internal.visit.application.VisitIdentity
import jakarta.servlet.http.HttpServletRequest
import java.time.Clock
import java.time.LocalDate
import java.util.regex.Pattern
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class VisitCollectionController(
    private val service: VisitCollection,
    private val cookies: VisitIdentity,
    private val clock: Clock,
    origins: String,
) {

    private val origins: Set<String> = origins.split(",")
        // Java String.trim semantics (characters <= U+0020); Kotlin's trim also strips NBSP and similar.
        .map { origin -> origin.trim { it <= ' ' } }
        .filter { origin -> origin.isNotEmpty() }
        .toSet()

    @PostMapping("/api/v1/visits/identity")
    fun identity(request: HttpServletRequest): ResponseEntity<*> {
        checkOrigin(request)
        if (bot(request)) {
            return noStore().build<Void>()
        }
        val cookie = cookies.initialize(cookie(request), clock.instant())
        val response = noStore()
        if (cookie != null) {
            response.header(
                HttpHeaders.SET_COOKIE,
                ResponseCookie.from(VisitIdentity.NAME, cookie.value)
                    .secure(true)
                    .httpOnly(true)
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(cookie.maxAgeSeconds)
                    .build()
                    .toString(),
            )
        }
        return response.build<Void>()
    }

    @PostMapping("/api/v1/visits/today")
    fun count(request: HttpServletRequest): ResponseEntity<*> {
        checkOrigin(request)
        if (bot(request)) {
            return noStore().build<Void>()
        }
        return ok(service.count(cookie(request)))
    }

    @GetMapping("/api/v1/admin/visits/stats/period")
    fun period(
        @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) startDate: LocalDate,
        @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDate: LocalDate,
    ): ResponseEntity<*> = ok(service.period(startDate, endDate))

    private fun ok(body: Any?): ResponseEntity<*> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(Response(200, "요청이 성공적으로 처리되었습니다.", body))

    private fun noStore(): ResponseEntity.BodyBuilder =
        ResponseEntity.status(HttpStatus.NO_CONTENT).cacheControl(CacheControl.noStore())

    private fun checkOrigin(request: HttpServletRequest) {
        val origin = request.getHeader("Origin")
        if (origin == null || !origins.contains(origin)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "허용된 출처의 요청만 가능합니다.")
        }
    }

    private fun bot(request: HttpServletRequest): Boolean {
        val userAgent = request.getHeader("User-Agent")
        return userAgent != null && KNOWN_BOT.matcher(userAgent).find()
    }

    private fun cookie(request: HttpServletRequest): String? {
        val requestCookies = request.cookies ?: return null
        // Reject ambiguous duplicate cookies, rather than choosing a different identity per request.
        val values = requestCookies.filter { candidate -> VisitIdentity.NAME == candidate.name }
        return if (values.size == 1) values[0].value else null
    }

    @JvmRecord
    data class Response<T>(val code: Int, val message: String?, val data: T?)

    private companion object {
        private val KNOWN_BOT: Pattern =
            Pattern.compile("(?i)(bot|crawler|spider|slurp|headlesschrome|facebookexternalhit)")
    }
}
