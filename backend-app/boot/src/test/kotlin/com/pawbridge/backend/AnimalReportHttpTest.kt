package com.pawbridge.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.report.application.AnimalReports
import com.pawbridge.backend.community.internal.report.application.AnimalReports.AnimalType
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Details
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Draft
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Filter
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Kind
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Slice
import com.pawbridge.backend.community.internal.report.web.AnimalReportController
import com.pawbridge.backend.community.internal.report.web.AnimalReportExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalDateTime

@WebMvcTest(
    controllers = [AnimalReportController::class],
    properties = ["pawbridge.animal-reports.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AnimalReportController::class,
        AnimalReportExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AnimalReportHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var reports: AnimalReports

    @Test
    fun `익명 독자일 때__목록과 상세를 조회하면__정확한 페이지 기본값과 22개 필드를 돌려준다`() {
        // given
        every { reports.list(any()) } returns Slice(listOf(details()), 1)
        every { reports.get(42) } returns details()

        // when
        val list = mvc.perform(get(ROOT))
        val detail = mvc.perform(get("$ROOT/42"))

        // then
        list.andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("요청이 성공적으로 처리되었습니다."))
            .andExpect(jsonPath("$.data.content[0].length()").value(22))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.totalPages").value(1))
            .andExpect(jsonPath("$.data.number").value(0))
            .andExpect(jsonPath("$.data.size").value(12))
            .andExpect(jsonPath("$.data.sort.sorted").value(true))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verify(exactly = 1) { reports.list(Filter(0, 12, null, null, null, null, null, null, null)) }
        detail.andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(22))
            .andExpect(jsonPath("$.data.kind").value("MISSING"))
            .andExpect(jsonPath("$.data.province").isEmpty())
    }

    @Test
    fun `모든 검색 필드를 줄 때__목록을 조회하면__원래 열거형과 날짜 이름으로 바인딩한다`() {
        // given
        every { reports.list(any()) } returns Slice(listOf(), 0)

        // when
        val response = mvc.perform(
            get(ROOT)
                .param("page", "2")
                .param("size", "5")
                .param("kind", "SIGHTING")
                .param("keyword", "개%")
                .param("province", "서울특별시")
                .param("district", "강남구")
                .param("animalType", "DOG")
                .param("from", "2026-10-01")
                .param("to", "2026-10-20"),
        )

        // then
        response.andExpect(status().isOk())
        verify(exactly = 1) {
            reports.list(
                Filter(
                    2,
                    5,
                    Kind.SIGHTING,
                    "개%",
                    "서울특별시",
                    "강남구",
                    AnimalType.DOG,
                    LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 10, 20),
                ),
            )
        }
    }

    @ParameterizedTest
    @CsvSource(
        "page,-1,페이지 범위를 확인해 주세요.",
        "size,51,페이지 범위를 확인해 주세요.",
        "page,wrong,제보 검색의 종류·날짜·페이지 형식을 확인해 주세요.",
        "kind,UNKNOWN,제보 검색의 종류·날짜·페이지 형식을 확인해 주세요.",
        "from,2026-99-01,제보 검색의 종류·날짜·페이지 형식을 확인해 주세요.",
    )
    fun `잘못된 검색 바인딩일 때__목록을 조회하면__서비스 전에 원래 400을 돌려준다`(
        key: String,
        value: String,
        message: String,
    ) {
        // when
        val response = mvc.perform(get(ROOT).param(key, value))

        // then
        response.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(message))
        confirmVerified(reports)
    }

    @Test
    fun `검증된 작성자와 위조된 헤더일 때__등록 수정 삭제하면__JWT 주체와 원래 봉투를 쓴다`() {
        // given
        every { reports.create(any(), isNull(), 7L) } returns details()
        every { reports.update(42L, any(), isNull(), 7L) } returns details()
        justRun { reports.delete(42, 7L) }

        // when
        val created = mvc.perform(
            multipart(ROOT).file(body(BODY)).header("Authorization", token()).header("X-User-Id", "999"),
        )
        val updated = mvc.perform(
            multipart(HttpMethod.PUT, "$ROOT/42").file(body(BODY)).header("Authorization", token()).header("X-User-Id", "999"),
        )
        val deleted = mvc.perform(delete("$ROOT/42").header("Authorization", token()).header("X-User-Id", "999"))

        // then
        created.andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("요청이 성공적으로 처리되었습니다."))
            .andExpect(jsonPath("$.data.authorId").value(7))
            .andExpect(header().doesNotExist("Set-Cookie"))
        updated.andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reportId").value(42))
        deleted.andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("제보가 삭제되었습니다."))
            .andExpect(jsonPath("$.data").isEmpty())
        verify(exactly = 1) { reports.create(match<Draft> { d -> d.kind == Kind.MISSING }, isNull(), 7L) }
        verify(exactly = 1) { reports.update(42L, any(), isNull(), 7L) }
        verify(exactly = 1) { reports.delete(42, 7L) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "PUT", "DELETE"])
    fun `JWT가 없고 본문이 잘못됐을 때__변경하면__인증이 바인딩보다 앞서고 위조 신원은 효과가 없다`(
        method: String,
    ) {
        // given
        val request = if (method == "DELETE") {
            delete("$ROOT/not-a-number")
        } else {
            multipart(HttpMethod.valueOf(method), ROOT + (if (method == "PUT") "/not-a-number" else "")).file(body("not-json"))
        }

        // when
        val response = mvc.perform(request.header("X-User-Id", "7").header("X-User-Role", "ROLE_ADMIN"))

        // then
        response.andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("Set-Cookie"))
        confirmVerified(reports)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 403, 404])
    fun `원본 정책 실패일 때__인증된 수정이 실행되면__상태와 사유를 유지한다`(code: Int) {
        // given
        val reason = if (code == 400) "제보 종류는 변경할 수 없습니다." else null

        every { reports.update(42L, any(), any(), 7L) } throws ResponseStatusException(HttpStatus.valueOf(code), reason)

        // when
        val response = mvc.perform(multipart(HttpMethod.PUT, "$ROOT/42").file(body(BODY)).header("Authorization", token()))

        // then
        response.andExpect(status().`is`(code))
            .andExpect(jsonPath("$.message").value(reason ?: "요청을 처리하지 못했습니다."))
    }

    @Test
    fun `JSON이나 ID가 잘못되고 이웃 경로가 노출되지 않을 때__인증 후 바인딩하면__400과 정확 경로 403을 유지한다`() {
        // when
        val badJson = mvc.perform(multipart(ROOT).file(body("not-json")).header("Authorization", token()))
        val badId = mvc.perform(delete("$ROOT/wrong").header("Authorization", token()))
        val getNeighbor = mvc.perform(get("$ROOT/42/extra"))
        val deleteNeighbor = mvc.perform(delete("$ROOT/42/extra").header("Authorization", token()))

        // then
        badJson.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("요청 본문 형식이 올바르지 않습니다."))
        badId.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("제보 검색의 종류·날짜·페이지 형식을 확인해 주세요."))
        getNeighbor.andExpect(status().isForbidden())
        deleteNeighbor.andExpect(status().isForbidden())
        confirmVerified(reports)
    }

    @Test
    fun `비공개 런타임 실패일 때__요청을 처리하면__일반 500과 유형만 남긴 로그를 낸다`() {
        // given
        val secret = "synthetic-private-report-body-storage-token"
        val log = LoggerFactory.getLogger(AnimalReportExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        log.addAppender(appender)

        every { reports.get(42) } throws IllegalStateException(secret)

        try {
            // when
            val response = mvc.perform(get("$ROOT/42"))

            // then
            response.andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("서버 에러!"))
            assertThat(appender.list).hasSize(1)
            assertThat(appender.list.first().formattedMessage).contains("IllegalStateException").doesNotContain(secret)
            assertThat(appender.list.first().throwableProxy).isNull()
        } finally {
            log.detachAppender(appender)
            appender.stop()
        }
    }

    companion object {
        private const val ROOT = "/api/v1/reports"
        private const val BODY =
            "{\"kind\":\"MISSING\",\"occurredOn\":\"2026-10-20\",\"region\":\"서울\",\"species\":\"개\",\"description\":\"내용\"}"

        private fun details(): Details {
            return Details(
                42L, 7L, "작성자", "실종 · 서울 · 개", "내용", listOf(), Kind.MISSING,
                LocalDate.of(2026, 10, 20), null, "서울", null, "개", null, null, null, null, null,
                LocalDateTime.of(2026, 10, 20, 12, 0), LocalDateTime.of(2026, 10, 20, 12, 0), null, null, null,
            )
        }

        private fun body(json: String): MockMultipartFile {
            return MockMultipartFile("report", "", "application/json", json.toByteArray(Charsets.UTF_8))
        }

        private fun token(): String = "Bearer " + F3TestInputs.token(7, "ROLE_USER")
    }
}

@WebMvcTest(
    controllers = [AnimalReportController::class],
    properties = ["pawbridge.animal-reports.enabled=false"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AnimalReportController::class,
        AnimalReportExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AnimalReportDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var reports: AnimalReports

    @ParameterizedTest
    @CsvSource(
        "GET,/api/v1/reports",
        "GET,/api/v1/reports/42",
        "POST,/api/v1/reports",
        "PUT,/api/v1/reports/42",
        "DELETE,/api/v1/reports/42",
    )
    fun `제보가 꺼졌을 때__공개 또는 인증 경로를 요청하면__제보 질의 전에 거부한다`(
        method: String,
        path: String,
    ) {
        // given
        val request = request(HttpMethod.valueOf(method), path)
        if (method != "GET") {
            request.header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
        }

        // when
        val response = mvc.perform(request)

        // then
        response.andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"))
        confirmVerified(reports)
    }
}
