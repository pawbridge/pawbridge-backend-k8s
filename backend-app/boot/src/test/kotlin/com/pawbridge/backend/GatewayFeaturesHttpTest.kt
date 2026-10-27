package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.boot.security.LostSearchLimitsFilter
import jakarta.servlet.Filter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext

/**
 * Real Security chain with the Gateway replacement filters in their production order:
 * Spring Security (CORS first), then the lost-search limits.
 */
@WebMvcTest(
    properties = [
        "pawbridge.animal-discovery.enabled=true",
        "pawbridge.animal-lost-search.enabled=true",
        "pawbridge.user-store-mypage.enabled=true",
        "pawbridge.payments.enabled=true",
    ],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [GatewayFeaturesHttpTest.Probe::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class GatewayFeaturesHttpTest {
    @RestController
    class Probe {
        @GetMapping("/api/v1/animals")
        fun animals(): String = "animals"

        @GetMapping("/api/v1/users/me/cart")
        fun cart(): String = "cart"

        @PostMapping("/api/v1/payments/confirm")
        fun confirm(): String = "confirmed"

        @PostMapping(value = ["/api/v1/animals/lost-candidates"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
        fun search(
            @RequestHeader(value = "Authorization", required = false) authorization: String?,
            @RequestHeader(value = "X-User-Id", required = false) user: String?,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) key: String?,
        ): String {
            return if (authorization == null && user == null && key == null) "clean" else "leaked"
        }
    }

    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    @Qualifier("springSecurityFilterChain")
    lateinit var security: Filter

    lateinit var mvc: MockMvc

    @BeforeEach
    fun chain() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters<DefaultMockMvcBuilder>(
                security,
                LostSearchLimitsFilter(1024, 2, 30, 50),
            )
            .build()
    }

    // ---- CORS ----

    @ParameterizedTest
    @CsvSource("/api/v1/posts", "/api/v1/products/3", "/api/v1/animals/lost-candidates")
    fun `브라우저 사전 요청일 때__토큰 없이 보내면__CORS 헤더와 함께 보안을 통과한다`(path: String) {
        // given
        val preflight = options(path)
            .header("Origin", "https://www.pawbridge.kr")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "authorization,content-type")

        // when
        val result = mvc.perform(preflight)

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://www.pawbridge.kr"))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
    }

    @Test
    fun `허용되지 않은 출처일 때__사전 요청하면__거절한다`() {
        // given
        val preflight = options("/api/v1/posts")
            .header("Origin", "https://evil.example")
            .header("Access-Control-Request-Method", "POST")

        // when
        val result = mvc.perform(preflight)

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
    }

    @Test
    fun `보안이 거부했을 때__허용된 출처가 요청하면__오류에도 CORS 헤더가 실린다`() {
        // given
        val request = request(HttpMethod.GET, "/api/v1/posts/anything").header("Origin", "https://pawbridge.kr")

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://pawbridge.kr"))
            .andExpect(header().string("Access-Control-Expose-Headers", "Retry-After, Authorization"))
    }

    @Test
    fun `허용되지 않은 출처일 때__공개 요청을 실제로 보내면__CORS 헤더 없이 정상 응답을 유지한다`() {
        // given
        val request = get("/api/v1/animals").header("Origin", "https://foreign.invalid")

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isOk())
            .andExpect(content().string("animals"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
    }

    @Test
    fun `결제 확인일 때__요청하면__인증이 필요하다`() {
        // given
        val path = "/api/v1/payments/confirm"
        val token = "Bearer " + F3TestInputs.token(7, "ROLE_USER")

        // when
        val withoutToken = mvc.perform(post(path))
        val invalidToken = mvc.perform(post(path).header("Authorization", "Bearer synthetic-invalid-token"))
        val validToken = mvc.perform(post(path).header("Authorization", token))

        // then
        withoutToken.andExpect(status().isUnauthorized())
        invalidToken.andExpect(status().isUnauthorized())
        validToken.andExpect(status().isOk()).andExpect(content().string("confirmed"))
    }

    @ParameterizedTest
    @CsvSource(
        "GET,/api/carts/reset",
        "POST,/api/carts/reset",
        "GET,/api/animals/lost-candidates",
        "POST,/api/animals/lost-candidates",
        "GET,/internal/animals/lost-gallery",
        "GET,/api/payments/unknown",
        "GET,/api/unknown",
        // old frontend aliases are no longer rewritten to /api/v1
        "GET,/api/animals",
        "GET,/api/users/me/cart",
        "POST,/api/payments/confirm",
    )
    fun `옛 경로나 게이트웨이가 라우팅하지 않던 경로일 때__요청하면__거부된다`(method: String, path: String) {
        // given
        val request = request(HttpMethod.valueOf(method), path)

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
    }

    @ParameterizedTest
    @CsvSource("/api/users/internal/5/nickname", "/api/v1/users/internal/5/nickname")
    fun `내부 사용자 경로일 때__요청하면__보안이 거부한다`(path: String) {
        // given
        val request = get(path)

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
    }

    // ---- health ----

    @Test
    fun `액추에이터일 때__익명으로 요청하면__GET health만 허용된다`() {
        // given
        val permitted = arrayOf("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")
        val denied = arrayOf(
            "/actuator", "/actuator/info", "/actuator/env", "/actuator/prometheus", "/actuator/metrics",
            "/actuator/beans", "/actuator/heapdump", "/actuator/health-x",
        )

        // when
        // then
        for (path in permitted) {
            // No actuator endpoints exist in this slice: permitted means "not 403" (MVC answers 404).
            assertThat(mvc.perform(get(path)).andReturn().response.status).`as`(path).isNotEqualTo(403)
        }
        for (path in denied) {
            mvc.perform(get(path)).andExpect(status().isForbidden())
        }
        mvc.perform(post("/actuator/health")).andExpect(status().isForbidden())
    }

    // ---- lost-candidates limits end to end ----

    @Test
    fun `작은 멀티파트일 때__검색하면__클라이언트 헤더가 핸들러에 닿지 않는다`() {
        // given
        val request = post("/api/v1/animals/lost-candidates")
            .contentType("multipart/form-data; boundary=x")
            .content("x")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
            .header("X-User-Id", "99")
            .header("X-Internal-Api-Key", "forged")

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isOk())
            .andExpect(content().string("clean"))
    }

    @Test
    fun `크기를 넘었거나 형식이 틀렸을 때__검색하면__게이트웨이 방식의 오류를 돌려준다`() {
        // given
        val oversized = post("/api/v1/animals/lost-candidates")
            .contentType("multipart/form-data; boundary=x")
            .content(ByteArray(1025))
        val wrongType = post("/api/v1/animals/lost-candidates")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}")

        // when
        val oversizedResult = mvc.perform(oversized)
        val wrongTypeResult = mvc.perform(wrongType)

        // then
        oversizedResult
            .andExpect(status().isPayloadTooLarge())
            .andExpect(content().json("{\"message\":\"사진 크기 제한을 초과했습니다\"}"))
        wrongTypeResult
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().json("{\"message\":\"사진 업로드 형식을 확인해 주세요\"}"))
    }
}
