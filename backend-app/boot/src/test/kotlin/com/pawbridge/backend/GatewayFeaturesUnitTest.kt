package com.pawbridge.backend

import com.pawbridge.backend.boot.security.GatewayCors
import com.pawbridge.backend.boot.security.LegacyApiPathFilter
import com.pawbridge.backend.boot.security.LostSearchLimitsFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** Pure servlet-level tests of the filters that replace the API Gateway; no Spring context or database. */
class GatewayFeaturesUnitTest {
    // ---- CORS ----

    @ParameterizedTest
    @CsvSource(
        "http://localhost:3000", "http://127.0.0.1:3000", "http://localhost:5173", "http://127.0.0.1:5173",
        "https://pawbridge.kr", "https://www.pawbridge.kr", "https://admin.pawbridge.kr",
    )
    fun `원래 게이트웨이 출처일 때__사전 요청하면__체인에 닿지 않고 응답한다`(origin: String) {
        // given
        val chain = MockFilterChain()

        // when
        val response = cors(DEFAULT_ORIGINS, preflight(origin, "PATCH", "authorization, content-type"), chain)

        // then
        assertThat(response.status).isEqualTo(200)
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin)
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true")
        assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
        assertThat(response.getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("authorization")
        assertThat(chain.request).isNull()
    }

    @ParameterizedTest
    @CsvSource(
        "https://evil.example,POST,authorization", "https://pawbridge.kr.evil.example,POST,authorization",
        "http://pawbridge.kr,POST,authorization", "https://pawbridge.kr,HEAD,authorization",
        "https://pawbridge.kr,POST,x-forbidden",
    )
    fun `허용되지 않은 출처, 메서드, 헤더일 때__사전 요청하면__거절한다`(
        origin: String,
        method: String,
        headers: String,
    ) {
        // given
        val request = preflight(origin, method, headers)

        // when
        val response = cors(DEFAULT_ORIGINS, request, MockFilterChain())

        // then
        assertThat(response.status).isEqualTo(403)
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull()
    }

    @Test
    fun `허용된 출처일 때__실제 요청하면__Retry-After와 Authorization을 노출한다`() {
        // given
        val request = MockHttpServletRequest("POST", "/api/v1/animals/lost-candidates")
        request.addHeader("Origin", "https://www.pawbridge.kr")
        val chain = MockFilterChain()

        // when
        val response = cors(DEFAULT_ORIGINS, request, chain)

        // then
        assertThat(chain.request).isNotNull()
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://www.pawbridge.kr")
        assertThat(response.getHeader("Access-Control-Expose-Headers")).contains("Retry-After", "Authorization")
    }

    @Test
    fun `허용되지 않은 출처일 때__실제 요청하면__CORS 헤더 없이 이어서 처리한다`() {
        // given
        val request = MockHttpServletRequest("GET", "/api/v1/visits/today")
        request.addHeader("Origin", "https://foreign.invalid")
        val chain = MockFilterChain()

        // when
        val response = cors(DEFAULT_ORIGINS, request, chain)

        // then
        assertThat(chain.request).isNotNull()
        assertThat(response.status).isEqualTo(200)
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull()
    }

    @Test
    fun `출처를 덮어썼을 때__사전 요청하면__덮어쓴 출처만 허용한다`() {
        // given
        val overridden = "https://staging.example"
        val allowedRequest = preflight("https://staging.example", "GET", null)
        val deniedRequest = preflight("https://pawbridge.kr", "GET", null)

        // when
        val allowedStatus = cors(overridden, allowedRequest, MockFilterChain()).status
        val deniedStatus = cors(overridden, deniedRequest, MockFilterChain()).status

        // then
        assertThat(allowedStatus).isEqualTo(200)
        assertThat(deniedStatus).isEqualTo(403)
    }

    // ---- legacy path rewrite ----

    @ParameterizedTest
    @CsvSource(
        "POST,/api/payments/confirm,/api/v1/payments/confirm", "GET,/api/payments/unknown,/api/v1/payments/unknown",
        "GET,/api/users/me,/api/v1/users/me", "PUT,/api/users/me/nickname,/api/v1/users/me/nickname",
        "POST,/api/auth/login,/api/v1/auth/login", "GET,/api/favorites/5/check,/api/v1/favorites/5/check",
        "GET,/api/animals,/api/v1/animals", "GET,/api/animals/7,/api/v1/animals/7", "POST,/api/animals,/api/v1/animals",
        "DELETE,/api/animals/7,/api/v1/animals/7", "PATCH,/api/shelters/3,/api/v1/shelters/3",
        "GET,/api/shelters/count,/api/v1/shelters/count",
        "POST,/api/animals/9/chat/messages,/api/v1/animals/9/chat/messages",
        "GET,/api/places,/api/v1/places", "GET,/api/places/regions,/api/v1/places/regions",
        "GET,/api/places/123,/api/v1/places/123",
        "GET,/api/chats/socket,/api/v1/chats/socket", "GET,/api/chats/abc/messages,/api/v1/chats/abc/messages",
        "POST,/api/chats/connection-ticket,/api/v1/chats/connection-ticket",
        "PUT,/api/notes/3/read,/api/v1/notes/3/read", "GET,/api/notes/stream,/api/v1/notes/stream",
        "GET,/api/notes,/api/v1/notes",
        "POST,/api/visits/identity,/api/v1/visits/identity", "GET,/api/visits/today,/api/v1/visits/today",
        "POST,/api/visits/today,/api/v1/visits/today",
        "GET,/api/admin/visits/stats/period,/api/v1/admin/visits/stats/period", "GET,/api/home/videos,/api/v1/home/videos",
        "GET,/api/admin/videos,/api/v1/admin/videos", "PUT,/api/admin/videos/3/publication,/api/v1/admin/videos/3/publication",
        "GET,/api/posts/read/3,/api/v1/posts/read/3", "POST,/api/posts,/api/v1/posts",
        "GET,/api/comments/posts/read/3,/api/v1/comments/posts/read/3",
        "DELETE,/api/reports/4,/api/v1/reports/4", "GET,/api/reports,/api/v1/reports",
        "GET,/api/products/5,/api/v1/products/5", "POST,/api/products,/api/v1/products",
        "DELETE,/api/categories/1,/api/v1/categories/1",
        "POST,/api/images,/api/v1/images", "GET,/api/option-groups/3,/api/v1/option-groups/3",
        "POST,/api/option-groups/3/values,/api/v1/option-groups/3/values",
        "PUT,/api/option-groups/values/9,/api/v1/option-groups/values/9",
        "GET,/api/admin/orders,/api/v1/admin/orders", "PATCH,/api/admin/orders/1/status,/api/v1/admin/orders/1/status",
        "GET,/api/carts,/api/v1/carts", "DELETE,/api/carts,/api/v1/carts", "POST,/api/carts/items,/api/v1/carts/items",
        "PATCH,/api/carts/items/9,/api/v1/carts/items/9",
        "DELETE,/api/carts/items/9,/api/v1/carts/items/9", "GET,/api/orders,/api/v1/orders",
        "POST,/api/orders/direct,/api/v1/orders/direct",
        "GET,/api/admin/users/stats/total-users,/api/v1/admin/users/stats/total-users",
        "GET,/api/admin/stats/daily-signups,/api/v1/admin/stats/daily-signups",
        "GET,/api/admin/posts/stats/today,/api/v1/admin/posts/stats/today",
        "GET,/api/admin/stats/intake-trend,/api/v1/admin/stats/intake-trend",
        "GET,/api/admin/stats/daily-animals,/api/v1/admin/stats/daily-animals",
    )
    fun `게이트웨이가 다시 쓰던 옛 경로일 때__필터링하면__v1 경로로 보여 준다`(
        method: String,
        path: String,
        expected: String,
    ) {
        // given
        // 파라미터로 주어진 method와 path를 그대로 쓴다

        // when
        val seen = legacy(method, path)

        // then
        assertThat(seen.uri).isEqualTo(expected)
        assertThat(seen.servletPath).isEqualTo(expected)
        assertThat(seen.pathInfo).isNull()
        assertThat(seen.query).isEqualTo("a=1")
        assertThat(seen.original).isEqualTo(path)
    }

    @ParameterizedTest
    @CsvSource(
        // never routed by the Gateway, or routed only for other methods
        "POST,/api/carts/reset", "DELETE,/api/carts/reset", "GET,/api/carts/items/9", "POST,/api/carts", "PUT,/api/carts",
        "POST,/api/places", "GET,/api/places/1/extra", "GET,/api/visits/identity", "PUT,/api/visits/today",
        "POST,/api/admin/visits/stats/period",
        "POST,/api/home/videos", "GET,/api/images", "GET,/api/option-groups/3/values", "POST,/api/admin/orders",
        "GET,/api/admin/stats/other",
        "HEAD,/api/animals", "TRACE,/api/products/1", "GET,/api/unknown", "GET,/api/batch/apms/sync",
        "POST,/api/batch/apms/sync",
        // the public upload must not become reachable through the old protected alias
        "POST,/api/animals/lost-candidates",
        // already served unchanged, or outside /api
        "POST,/api/v1/payments/confirm", "GET,/api/v1/animals", "POST,/api/v1/animals/lost-candidates",
        "GET,/internal/animals/lost-gallery",
        "GET,/oauth2/authorization/google", "GET,/login/oauth2/code/google", "GET,/actuator/health", "GET,/api/v1",
        "GET,/apix/animals",
    )
    fun `게이트웨이가 다시 쓰지 않던 경로나 메서드일 때__필터링하면__그대로 둔다`(
        method: String,
        path: String,
    ) {
        // given
        // 파라미터로 주어진 method와 path를 그대로 쓴다

        // when
        val seen = legacy(method, path)

        // then
        assertThat(seen.uri).isEqualTo(path)
        assertThat(seen.original).isNull()
        assertThat(seen.status).isEqualTo(200)
    }

    @ParameterizedTest
    @CsvSource("GET,/api/users/internal/5/nickname", "PUT,/api/v1/users/internal/5/state", "DELETE,/api/users/internal/x")
    fun `서비스 간 사용자 경로일 때__필터링하면__게이트웨이 404로 거절한다`(
        method: String,
        path: String,
    ) {
        // given
        val request = MockHttpServletRequest(method, path)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        // when
        LegacyApiPathFilter().doFilter(request, response, chain)

        // then
        assertThat(chain.request).isNull()
        assertThat(response.status).isEqualTo(404)
        assertThat(response.getContentAsString(Charsets.UTF_8))
            .isEqualTo("{\"code\":404,\"message\":\"유효하지 않은 엔드포인트입니다.\",\"data\":null}")
    }

    @Test
    fun `퍼센트 인코딩된 내부 사용자 경로일 때__필터링하면__여전히 404다`() {
        // given
        val paths = arrayOf(
            "/api/users/%69nternal/5/nickname",
            "/api/v1/users/internal%2F".replace("%2F", "/") + "5",
            "/api/v1/%75sers/internal/5",
        )

        for (path in paths) {
            val request = MockHttpServletRequest("GET", path)
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            // when
            LegacyApiPathFilter().doFilter(request, response, chain)

            // then
            assertThat(chain.request).`as`(path).isNull()
            assertThat(response.status).isEqualTo(404)
        }
    }

    @Test
    fun `인코딩된 나머지 경로일 때__다시 쓰면__서블릿 경로는 디코딩되고 URI는 인코딩을 유지한다`() {
        // given
        val request = MockHttpServletRequest("GET", "/api/posts/read/a%20b")
        val chain = MockFilterChain()

        // when
        LegacyApiPathFilter().doFilter(request, MockHttpServletResponse(), chain)

        // then
        val seen = chain.request as HttpServletRequest
        assertThat(seen.requestURI).isEqualTo("/api/v1/posts/read/a%20b")
        assertThat(seen.servletPath).isEqualTo("/api/v1/posts/read/a b")
    }

    // ---- lost-candidates limits ----

    @Test
    fun `본문이 한도보다 클 때__검색하면__413을 돌려준다`() {
        // given
        val filter = LostSearchLimitsFilter(10, 2, 30, 50)
        val chain = MockFilterChain()

        // when
        val oversized = runFilter(filter, search(TYPE, ByteArray(11)), chain)

        // then
        assertRejected(oversized, 413, "사진 크기 제한을 초과했습니다", null)
        assertThat(chain.request).isNull()
        // exactly at the limit is admitted
        assertThat(runFilter(filter, search(TYPE, ByteArray(10)), chain).status).isEqualTo(200)
        assertThat(chain.request).isNotNull()
    }

    @Test
    fun `퍼센트 인코딩된 경로일 때__검색하면__같은 한도가 적용된다`() {
        // given
        val filter = LostSearchLimitsFilter(10, 2, 30, 50)
        val paths = arrayOf(
            "/api/v1/animals/lost-%63andidates",
            "/api/v1/animals/lost-candidates;x=1",
            "/api/v1/animals/%6cost-candidates/",
        )

        for (path in paths) {
            val request = search(TYPE, ByteArray(11))
            request.requestURI = path

            // when
            val response = runFilter(filter, request, MockFilterChain())

            // then
            assertRejected(response, 413, "사진 크기 제한을 초과했습니다", null)
        }
    }

    @Test
    fun `멀티파트가 아니거나 형식이 깨졌을 때__검색하면__415를 돌려준다`() {
        // given
        val filter = LostSearchLimitsFilter(100, 2, 30, 50)

        for (type in arrayOf<String?>(null, "application/json", "text/plain", "multipart", "not a media type")) {
            // when
            val response = runFilter(filter, search(type, byteArrayOf(1)), MockFilterChain())

            // then
            assertRejected(response, 415, "사진 업로드 형식을 확인해 주세요", null)
        }
    }

    @Test
    fun `선언된 길이가 없을 때__검색하면__세기 전에 거절한다`() {
        // given
        val filter = LostSearchLimitsFilter(100, 1, 1, 50)

        // when
        val refused = runFilter(filter, search(TYPE, null), MockFilterChain())

        // then
        assertRejected(refused, 411, "사진 업로드 형식을 확인해 주세요", null)
        // the refusal consumed neither the concurrency permit nor the per-minute budget
        assertThat(runFilter(filter, search(TYPE, byteArrayOf(1)), MockFilterChain()).status).isEqualTo(200)
    }

    @Test
    fun `본문이 비었을 때__검색하면__400을 돌려준다`() {
        // given
        val filter = LostSearchLimitsFilter(100, 2, 30, 50)

        // when
        val response = runFilter(filter, search(TYPE, ByteArray(0)), MockFilterChain())

        // then
        assertRejected(response, 400, "사진을 선택해 주세요", null)
    }

    @Test
    fun `요청 두 건이 처리 중일 때__세 번째가 오면__Retry-After 3과 함께 503을 돌려준다`() {
        // given
        val filter = LostSearchLimitsFilter(100, 2, 30, 50)
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val slow = FilterChain { _, _ ->
            entered.countDown()
            try {
                release.await(10, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val workers = Array(2) {
            val worker = Thread {
                try {
                    runFilter(filter, search(TYPE, byteArrayOf(1)), slow)
                } catch (ignored: Exception) {
                }
            }
            worker.start()
            worker
        }
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue()

        // when
        val third = runFilter(filter, search(TYPE, byteArrayOf(1)), MockFilterChain())
        release.countDown()
        for (worker in workers) {
            worker.join(10_000)
        }

        // then
        assertRejected(third, 503, "잠시 후 다시 시도해 주세요", "3")
        // permits return after completion
        assertThat(runFilter(filter, search(TYPE, byteArrayOf(1)), MockFilterChain()).status).isEqualTo(200)
    }

    @Test
    fun `분당 예산을 다 썼을 때__검색하면__Retry-After 60과 함께 429를 돌려주고 허가를 반납한다`() {
        // given
        val filter = LostSearchLimitsFilter(100, 1, 2, 50)
        val first = runFilter(filter, search(TYPE, byteArrayOf(1)), MockFilterChain())
        val second = runFilter(filter, search(TYPE, byteArrayOf(1)), MockFilterChain())

        // when
        val third = runFilter(filter, search(TYPE, byteArrayOf(1)), MockFilterChain())
        val fourth = runFilter(filter, search(TYPE, byteArrayOf(1)), MockFilterChain())

        // then
        assertThat(first.status).isEqualTo(200)
        assertThat(second.status).isEqualTo(200)
        assertRejected(third, 429, "검색 요청이 많습니다. 잠시 후 다시 시도해 주세요", "60")
        // the 429 released its concurrency permit instead of leaking it (a leak would turn this into 503)
        assertRejected(fourth, 429, "검색 요청이 많습니다. 잠시 후 다시 시도해 주세요", "60")
    }

    @Test
    fun `핸들러에서 시간 초과가 새어 나올 때__검색하면__504를 돌려준다`() {
        // given
        val filter = LostSearchLimitsFilter(100, 1, 30, 50)
        val timesOut = FilterChain { _, _ -> throw ServletException(TimeoutException()) }
        val broken = FilterChain { _, _ -> throw ServletException(IllegalStateException("boom")) }

        // when
        val timedOut = runFilter(filter, search(TYPE, byteArrayOf(1)), timesOut)
        val brokenThrown = assertThatThrownBy { runFilter(filter, search(TYPE, byteArrayOf(1)), broken) }

        // then
        assertRejected(timedOut, 504, "검색 시간이 초과됐습니다", null)
        brokenThrown.isInstanceOf(ServletException::class.java)
        assertThat(runFilter(filter, search(TYPE, byteArrayOf(1)), MockFilterChain()).status).isEqualTo(200)
    }

    @Test
    fun `허용된 요청일 때__전달하면__클라이언트 신원과 내부 키 헤더를 숨긴다`() {
        // given
        val filter = LostSearchLimitsFilter(100, 2, 30, 50)
        val seen = AtomicReference<HttpServletRequest>()

        // when
        val response = runFilter(filter, search(TYPE, byteArrayOf(1)), FilterChain { req, _ -> seen.set(req as HttpServletRequest) })

        // then
        assertThat(response.status).isEqualTo(200)
        val forwarded = seen.get()
        for (name in arrayOf("Authorization", "authorization", "X-User-Id", "X-Internal-Api-Key", "Transfer-Encoding")) {
            assertThat(forwarded.getHeader(name)).isNull()
            assertThat(Collections.list(forwarded.getHeaders(name))).isEmpty()
        }
        assertThat(Collections.list(forwarded.headerNames).map { it.lowercase() })
            .contains("accept", "content-type")
            .doesNotContain("authorization", "x-user-id", "x-internal-api-key")
    }

    @Test
    fun `다른 경로일 때__필터링하면__한도를 적용하지 않는다`() {
        // given
        val filter = LostSearchLimitsFilter(1, 1, 1, 50)
        val routes = listOf(
            arrayOf("GET", LostSearchLimitsFilter.PATH),
            arrayOf("POST", "/api/v1/animals"),
            arrayOf("POST", "/api/v1/animals/lost-candidates/extra"),
        )

        for (route in routes) {
            val chain = MockFilterChain()
            val request = MockHttpServletRequest(route[0], route[1])

            // when
            val response = runFilter(filter, request, chain)

            // then
            assertThat(response.status).isEqualTo(200)
            assertThat(chain.request).isNotNull()
        }
    }

    @Test
    fun `한도가 양수가 아닐 때__생성하면__거절한다`() {
        // given
        // 한도 값 하나씩이 0인 필터 생성 람다를 아래에서 호출한다

        // when
        val zeroBytes = assertThatThrownBy { LostSearchLimitsFilter(0, 2, 30, 50) }
        val zeroConcurrent = assertThatThrownBy { LostSearchLimitsFilter(1, 0, 30, 50) }
        val zeroPerMinute = assertThatThrownBy { LostSearchLimitsFilter(1, 2, 0, 50) }
        val zeroTimeout = assertThatThrownBy { LostSearchLimitsFilter(1, 2, 30, 0) }

        // then
        zeroBytes.isInstanceOf(IllegalArgumentException::class.java)
        zeroConcurrent.isInstanceOf(IllegalArgumentException::class.java)
        zeroPerMinute.isInstanceOf(IllegalArgumentException::class.java)
        zeroTimeout.isInstanceOf(IllegalArgumentException::class.java)
    }

    private data class Seen(
        val uri: String?,
        val servletPath: String?,
        val pathInfo: String?,
        val query: String?,
        val original: String?,
        val status: Int,
    )

    companion object {
        private val DEFAULT_ORIGINS: String = GatewayCors.DEFAULT_ORIGIN_PATTERNS
        private const val TYPE = "multipart/form-data; boundary=x"

        private fun cors(origins: String, request: MockHttpServletRequest, chain: MockFilterChain): MockHttpServletResponse {
            val response = MockHttpServletResponse()
            GatewayCors.filter(origins).doFilter(request, response, chain)
            return response
        }

        private fun preflight(origin: String, method: String, headers: String?): MockHttpServletRequest {
            val request = MockHttpServletRequest("OPTIONS", "/api/v1/posts")
            request.addHeader("Origin", origin)
            request.addHeader("Access-Control-Request-Method", method)
            if (headers != null) {
                request.addHeader("Access-Control-Request-Headers", headers)
            }
            return request
        }

        private fun legacy(method: String, path: String): Seen {
            val request = MockHttpServletRequest(method, path)
            request.queryString = "a=1"
            request.servletPath = path
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()
            LegacyApiPathFilter().doFilter(request, response, chain)
            val seen = chain.request as HttpServletRequest?
            if (seen == null) {
                return Seen(null, null, null, null, null, response.status)
            }
            return Seen(
                seen.requestURI,
                seen.servletPath,
                seen.pathInfo,
                seen.queryString,
                seen.getAttribute(LegacyApiPathFilter.ORIGINAL_PATH_ATTRIBUTE) as String?,
                response.status,
            )
        }

        private fun search(contentType: String?, body: ByteArray?): MockHttpServletRequest {
            val request = MockHttpServletRequest("POST", LostSearchLimitsFilter.PATH)
            if (contentType != null) {
                request.contentType = contentType
            }
            if (body != null) {
                request.setContent(body)
            }
            request.addHeader("Authorization", "Bearer secret")
            request.addHeader("X-User-Id", "99")
            request.addHeader("X-Internal-Api-Key", "forged")
            request.addHeader("Accept", "application/json")
            return request
        }

        private fun runFilter(
            filter: LostSearchLimitsFilter,
            request: MockHttpServletRequest,
            chain: FilterChain,
        ): MockHttpServletResponse {
            val response = MockHttpServletResponse()
            filter.doFilter(request, response, chain)
            return response
        }

        private fun assertRejected(response: MockHttpServletResponse, status: Int, message: String, retryAfter: String?) {
            assertThat(response.status).isEqualTo(status)
            assertThat(response.contentType).startsWith("application/json")
            assertThat(response.getContentAsString(Charsets.UTF_8)).isEqualTo("{\"message\":\"$message\"}")
            assertThat(response.getHeader("Retry-After")).isEqualTo(retryAfter)
        }
    }
}
