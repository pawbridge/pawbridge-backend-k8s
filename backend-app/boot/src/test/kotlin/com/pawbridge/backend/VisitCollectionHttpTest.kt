package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.visit.application.TodayVisits
import com.pawbridge.backend.community.internal.visit.application.VisitCollection
import com.pawbridge.backend.community.internal.visit.application.VisitCollectionRejectedException
import com.pawbridge.backend.community.internal.visit.application.VisitIdentity
import com.pawbridge.backend.community.internal.visit.web.VisitCollectionController
import com.pawbridge.backend.community.internal.visit.web.VisitCollectionExceptionHandler
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

@WebMvcTest(
    controllers = [VisitCollectionController::class],
    properties = ["pawbridge.visits.enabled=true", "pawbridge.visits.collection-enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        VisitCollectionController::class,
        VisitCollectionExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
        VisitCollectionHttpTest.Inputs::class,
    ],
)
class VisitCollectionHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var identity: VisitIdentity

    @MockitoBean
    lateinit var visits: VisitCollection

    @TestConfiguration(proxyBeanMethods = false)
    class Inputs {
        @Bean
        fun identity(): VisitIdentity = VisitIdentity(Base64.getEncoder().encodeToString(ByteArray(32)))

        @Bean
        fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        @Bean
        fun origins(): String = ORIGIN
    }

    @Test
    fun given__first_and_existing_identity__when__initializing__then__host_cookie_until_midnight_without_count() {
        val response = mvc.perform(
            post("/api/v1/visits/identity")
                .header("Origin", ORIGIN)
                .header("Authorization", "Bearer invalid")
                .header("X-User-Id", "999"),
        )
            .andExpect(status().isNoContent())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().string(""))
            .andReturn().response
        val cookie = response.getHeader("Set-Cookie")!!
        assertThat(cookie)
            .contains("__Host-pb_visit=", "Path=/", "Secure", "HttpOnly", "SameSite=Lax", "Max-Age=60")
            .doesNotContain("Domain=")
        val value = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'))
        assertThat(identity.identity(value, LocalDate.of(2026, 10, 12))).isNotNull()
        mvc.perform(post("/api/v1/visits/identity").header("Origin", ORIGIN).cookie(Cookie(VisitIdentity.NAME, value)))
            .andExpect(status().isNoContent())
            .andExpect(header().doesNotExist("Set-Cookie"))
        verifyNoInteractions(visits)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "https://outside.fixture.invalid"])
    fun given__unapproved_origin_even_for_bot__when__posting__then__403_before_cookie_or_storage(origin: String) {
        val request = post("/api/v1/visits/identity").header("User-Agent", "crawler")
        if (origin != "missing") {
            request.header("Origin", origin)
        }
        mvc.perform(request)
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":403,\"message\":\"허용된 출처의 요청만 가능합니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verifyNoInteractions(visits)
    }

    @Test
    fun given__known_bot__when__initializing_or_counting__then__204_no_cookie_or_storage() {
        for (path in listOf("identity", "today")) {
            mvc.perform(post("/api/v1/visits/$path").header("Origin", ORIGIN).header("User-Agent", "HeadlessChrome"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().string(""))
        }
        verifyNoInteractions(visits)
    }

    @Test
    fun given__cookie_and_untrusted_identity_headers__when__counting__then__exact_day_count_without_identity_echo_or_mint() {
        val cookie = identity.initialize(null, NOW)!!.value
        whenever(visits.count(cookie)).thenReturn(TodayVisits(LocalDate.of(2026, 10, 12), 7))
        mvc.perform(
            post("/api/v1/visits/today")
                .header("Origin", ORIGIN)
                .header("X-User-Id", "999")
                .header("Authorization", "Bearer invalid")
                .cookie(Cookie(VisitIdentity.NAME, cookie)),
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"date\":\"2026-10-12\",\"count\":7}}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(visits).count(cookie)
    }

    @Test
    fun given__missing_or_ambiguous_cookie__when__counting__then__409_without_mint() {
        whenever(visits.count(null))
            .thenThrow(VisitCollectionRejectedException(VisitCollectionRejectedException.Reason.COOKIE))
        for (duplicate in listOf(false, true)) {
            val request = post("/api/v1/visits/today").header("Origin", ORIGIN)
            if (duplicate) {
                request.cookie(Cookie(VisitIdentity.NAME, "synthetic-one"), Cookie(VisitIdentity.NAME, "synthetic-two"))
            }
            mvc.perform(request)
                .andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(
                    content().json(
                        "{\"code\":409,\"message\":\"방문 쿠키 초기화가 필요합니다.\",\"data\":null}",
                        JsonCompareMode.STRICT,
                    ),
                )
        }
        verify(visits, times(2)).count(null)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "invalid", "USER"])
    fun given__no_actual_admin_token_despite_role_header__when__reading_period__then__deny_without_query(role: String) {
        val request = get("/api/v1/admin/visits/stats/period")
            .param("startDate", "2026-10-12")
            .param("endDate", "2026-10-12")
            .header("X-User-Role", "ROLE_ADMIN")
            .header("X-User-Id", "1")
        if (role == "invalid") {
            request.header("Authorization", "Bearer invalid")
        }
        if (role == "USER") {
            request.header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
        }
        mvc.perform(request).andExpect(status().`is`(if (role == "USER") 403 else 401))
        verifyNoInteractions(visits)
    }

    @Test
    fun given__actual_admin_and_dates__when__reading_period__then__exact_envelope_and_explicit_parameter_binding() {
        val day = LocalDate.of(2026, 10, 12)
        whenever(visits.period(day, day))
            .thenReturn(VisitCollection.Period(day, day, day, NOW, listOf(VisitCollection.Day(day, 7))))
        val token = F3TestInputs.token(7, "ROLE_ADMIN")
        mvc.perform(
            get("/api/v1/admin/visits/stats/period")
                .header("Authorization", "Bearer $token")
                .header("X-User-Role", "ROLE_USER")
                .param("startDate", day.toString())
                .param("endDate", day.toString()),
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"startDate\":\"2026-10-12\",\"endDate\":\"2026-10-12\",\"today\":\"2026-10-12\",\"collectionStartedAt\":\"2026-10-12T14:59:00Z\",\"daily\":[{\"date\":\"2026-10-12\",\"count\":7}]}}",
                    JsonCompareMode.STRICT,
                ),
            )
        mvc.perform(
            get("/api/v1/admin/visits/stats/period")
                .header("Authorization", "Bearer $token")
                .param("startDate", "bad")
                .param("endDate", day.toString()),
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("날짜 형식을 확인해 주세요."))
        mvc.perform(
            get("/api/v1/admin/visits/stats/period")
                .header("Authorization", "Bearer $token")
                .param("startDate", day.toString()),
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("필수 요청 파라미터 'endDate'(이)가 누락되었습니다."))
        verify(visits).period(day, day)
    }

    @Test
    fun given__storage_or_runtime_failure__when__counting__then__safe_error_without_private_details() {
        whenever(visits.count(null)).thenThrow(
            DataAccessResourceFailureException("synthetic-private-sql"),
            IllegalStateException("synthetic-private-key"),
        )
        mvc.perform(post("/api/v1/visits/today").header("Origin", ORIGIN))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(
                content().json(
                    "{\"code\":503,\"message\":\"방문 통계를 불러올 수 없습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        mvc.perform(post("/api/v1/visits/today").header("Origin", ORIGIN))
            .andExpect(status().isInternalServerError())
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-10-12T14:59:00Z")
        const val ORIGIN: String = "https://www.pawbridge.kr"
    }
}
