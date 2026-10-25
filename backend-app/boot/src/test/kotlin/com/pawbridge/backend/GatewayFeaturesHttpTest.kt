package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.boot.security.LegacyApiPathFilter
import com.pawbridge.backend.boot.security.LostSearchLimitsFilter
import jakarta.servlet.Filter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
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
 * legacy rewrite, then Spring Security (CORS first), then the lost-search limits.
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
                LegacyApiPathFilter(),
                security,
                LostSearchLimitsFilter(1024, 2, 30, 50),
            )
            .build()
    }

    // ---- CORS ----

    @ParameterizedTest
    @CsvSource("/api/v1/posts", "/api/posts", "/api/v1/products/3", "/api/v1/animals/lost-candidates")
    fun given__browser_preflight__when__no_token__then__pass_security_with_cors_headers(path: String) {
        mvc.perform(
            options(path)
                .header("Origin", "https://www.pawbridge.kr")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type"),
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://www.pawbridge.kr"))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
    }

    @Test
    fun given__foreign_origin__when__preflight__then__reject() {
        mvc.perform(
            options("/api/v1/posts")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST"),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
    }

    @Test
    fun given__security_denial__when__allowed_origin_requests__then__the_error_still_carries_cors_headers() {
        mvc.perform(request(HttpMethod.GET, "/api/v1/posts/anything").header("Origin", "https://pawbridge.kr"))
            .andExpect(status().isForbidden())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://pawbridge.kr"))
            .andExpect(header().string("Access-Control-Expose-Headers", "Retry-After, Authorization"))
    }

    @Test
    fun given__foreign_origin__when__actual_public_request__then__keep_the_normal_response_without_cors_headers() {
        mvc.perform(get("/api/v1/animals").header("Origin", "https://foreign.invalid"))
            .andExpect(status().isOk())
            .andExpect(content().string("animals"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
    }

    // ---- legacy paths evaluated by Security and MVC as /api/v1 ----

    @Test
    fun given__old_public_path__when__requested__then__security_and_mvc_use_the_v1_path() {
        mvc.perform(get("/api/animals")).andExpect(status().isOk()).andExpect(content().string("animals"))
        mvc.perform(get("/api/v1/animals")).andExpect(status().isOk()).andExpect(content().string("animals"))
    }

    @Test
    fun given__old_protected_path__when__requested__then__the_v1_authentication_rule_applies() {
        val token = "Bearer " + F3TestInputs.token(7, "ROLE_USER")
        mvc.perform(get("/api/users/me/cart")).andExpect(status().isUnauthorized())
        mvc.perform(get("/api/v1/users/me/cart")).andExpect(status().isUnauthorized())
        mvc.perform(get("/api/users/me/cart").header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(content().string("cart"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/payments/confirm", "/api/v1/payments/confirm"])
    fun given__payment_confirmation__when__requested__then__authentication_is_required_on_both_spellings(path: String) {
        val token = "Bearer " + F3TestInputs.token(7, "ROLE_USER")
        mvc.perform(post(path)).andExpect(status().isUnauthorized())
        mvc.perform(post(path).header("Authorization", "Bearer synthetic-invalid-token")).andExpect(status().isUnauthorized())
        mvc.perform(post(path).header("Authorization", token)).andExpect(status().isOk()).andExpect(content().string("confirmed"))
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
    )
    fun given__path_the_gateway_never_routed__when__requested__then__stay_denied(method: String, path: String) {
        mvc.perform(request(HttpMethod.valueOf(method), path)).andExpect(status().isForbidden())
    }

    @ParameterizedTest
    @CsvSource("/api/users/internal/5/nickname", "/api/v1/users/internal/5/nickname")
    fun given__internal_user_path__when__requested__then__404_before_security(path: String) {
        mvc.perform(get(path))
            .andExpect(status().isNotFound())
            .andExpect(content().json("{\"code\":404,\"message\":\"유효하지 않은 엔드포인트입니다.\",\"data\":null}"))
    }

    // ---- health ----

    @Test
    fun given__actuator__when__requested_anonymously__then__only_get_health_is_permitted() {
        for (path in arrayOf("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            // No actuator endpoints exist in this slice: permitted means "not 403" (MVC answers 404).
            assertThat(mvc.perform(get(path)).andReturn().response.status).`as`(path).isNotEqualTo(403)
        }
        val denied = arrayOf(
            "/actuator", "/actuator/info", "/actuator/env", "/actuator/prometheus", "/actuator/metrics",
            "/actuator/beans", "/actuator/heapdump", "/actuator/health-x",
        )
        for (path in denied) {
            mvc.perform(get(path)).andExpect(status().isForbidden())
        }
        mvc.perform(post("/actuator/health")).andExpect(status().isForbidden())
    }

    // ---- lost-candidates limits end to end ----

    @Test
    fun given__small_multipart__when__searching__then__client_headers_never_reach_the_handler() {
        mvc.perform(
            post("/api/v1/animals/lost-candidates")
                .contentType("multipart/form-data; boundary=x")
                .content("x")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .header("X-User-Id", "99")
                .header("X-Internal-Api-Key", "forged"),
        )
            .andExpect(status().isOk())
            .andExpect(content().string("clean"))
    }

    @Test
    fun given__oversized_or_wrong_type__when__searching__then__gateway_style_errors() {
        mvc.perform(
            post("/api/v1/animals/lost-candidates").contentType("multipart/form-data; boundary=x").content(ByteArray(1025)),
        )
            .andExpect(status().isPayloadTooLarge())
            .andExpect(content().json("{\"message\":\"사진 크기 제한을 초과했습니다\"}"))
        mvc.perform(post("/api/v1/animals/lost-candidates").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().json("{\"message\":\"사진 업로드 형식을 확인해 주세요\"}"))
    }
}
