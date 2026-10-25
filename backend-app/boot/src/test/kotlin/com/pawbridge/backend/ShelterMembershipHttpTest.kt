package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembershipRejectedException
import com.pawbridge.backend.user.internal.shelter.web.AdminShelterApplicationController
import com.pawbridge.backend.user.internal.shelter.web.AdminShelterMemberController
import com.pawbridge.backend.user.internal.shelter.web.ShelterApplicationController
import com.pawbridge.backend.user.internal.shelter.web.ShelterMembershipExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.TransactionSystemException
import java.time.LocalDate
import java.time.LocalDateTime

@WebMvcTest(
    properties = ["pawbridge.shelter-membership.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        ShelterApplicationController::class,
        AdminShelterApplicationController::class,
        AdminShelterMemberController::class,
        ShelterMembershipExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class ShelterMembershipHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var memberships: ShelterMembership

    @Test
    fun `검증된 주체와 여덟 경로가 주어졌을 때__호출하면__명시 ID와 기본값과 날짜와 본문과 원래 봉투를 바인딩한다`() {
        // given
        val row = row()
        val admin = ShelterMembership.AdminApplication(row, "이름", "fixture@example.invalid", null)
        val day = LocalDate.of(2026, 10, 20)

        every { memberships.submit(any(), any()) } returns row
        every { memberships.mine(any(), any(), any()) } returns ShelterMembership.Page(listOf(row), 1, 0, 20)
        every { memberships.list(any(), any(), any(), any()) } returns ShelterMembership.Page(listOf(admin), 1, 0, 20)
        every { memberships.detail(any(), any()) } returns admin
        every { memberships.approve(any(), any(), any(), any()) } returns admin
        every { memberships.reject(any(), any(), any()) } returns admin
        every { memberships.members(any(), any(), any(), any()) } returns
            ShelterMembership.Page(listOf(ShelterMembership.Member(8L, "이름", null, null)), 1, 0, 20)
        every { memberships.statistics(any(), any(), any()) } returns
            ShelterMembership.Statistics(day, day, listOf(), 2, 3, 4, 5)

        // when
        val submitted = mvc.perform(
            post(OWN)
                .header("Authorization", token("ROLE_USER"))
                .header("X-User-Id", "999")
                .contentType("application/json")
                .content("{\"shelterName\":\" raw \"}"),
        )
        val mine = mvc.perform(get(OWN).header("Authorization", token("ROLE_USER")))
        val listed = mvc.perform(get(ADMIN).param("status", "PENDING").header("Authorization", token("ROLE_ADMIN")))
        val detailed = mvc.perform(get("$ADMIN/91").header("Authorization", token("ROLE_ADMIN")))
        val approved = mvc.perform(
            post("$ADMIN/91/approve")
                .header("Authorization", token("ROLE_ADMIN"))
                .contentType("application/json")
                .content("{\"careRegNo\":\"reg\",\"note\":\"note\"}"),
        )
        val rejected = mvc.perform(
            post("$ADMIN/91/reject")
                .header("Authorization", token("ROLE_ADMIN"))
                .contentType("application/json")
                .content("{\"reason\":\"reason\"}"),
        )
        val members = mvc.perform(
            get("/api/v1/admin/users/shelters/reg/members").header("Authorization", token("ROLE_ADMIN")),
        )
        val statistics = mvc.perform(
            get("$ADMIN/stats")
                .param("startDate", day.toString())
                .param("endDate", day.toString())
                .header("Authorization", token("ROLE_ADMIN")),
        )

        // then
        submitted
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.requestedAt").value("2026-10-20T01:02:03.123456"))
            .andExpect(jsonPath("$.data.reason").isEmpty())
            .andExpect(header().doesNotExist("Set-Cookie"))
        mine
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.sort.sorted").value(true))
        listed
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].application.id").value(91))
        detailed
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.applicantName").value("이름"))
        approved.andExpect(status().isOk())
        rejected.andExpect(status().isOk())
        members
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].approvalApplicationId").isEmpty())
        statistics
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.currentPending").value(3))
        verify(exactly = 1) { memberships.submit(7, " raw ") }
        verify(exactly = 1) { memberships.mine(7, 0, 20) }
        verify(exactly = 1) { memberships.list(7, ShelterMembership.Status.PENDING, 0, 20) }
        verify(exactly = 1) { memberships.detail(7, 91) }
        verify(exactly = 1) { memberships.approve(7, 91, "reg", "note") }
        verify(exactly = 1) { memberships.reject(7, 91, "reason") }
        verify(exactly = 1) { memberships.members(7, "reg", 0, 20) }
        verify(exactly = 1) { memberships.statistics(7, day, day) }
        confirmVerified(memberships)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun `인증된 역할이 주어졌을 때__내 신청을 읽으면__서비스가 현재 DB 정책을 적용하게 둔다`(
        role: String,
    ) {
        // given
        every { memberships.mine(any(), any(), any()) } returns ShelterMembership.Page(listOf(), 0, 0, 20)

        // when
        val result = mvc.perform(get(OWN).header("Authorization", token(role)))

        // then
        result.andExpect(status().isOk())
        verify(exactly = 1) { memberships.mine(7, 0, 20) }
        cleared()
    }

    @Test
    fun `JWT가 없거나 관리자 역할이 위조됐을 때__잘못된 ID와 본문을 보내면__바인딩 전에 인증과 인가를 한다`() {
        // given
        // when
        val missingJwt = mvc.perform(
            post("$ADMIN/bad/approve")
                .header("X-User-Role", "ROLE_ADMIN")
                .contentType("application/json")
                .content("{"),
        )
        val forgedRole = mvc.perform(
            post("$ADMIN/bad/approve")
                .header("Authorization", token("ROLE_USER"))
                .header("X-User-Role", "ROLE_ADMIN")
                .contentType("application/json")
                .content("{"),
        )

        // then
        missingJwt.andExpect(status().isUnauthorized())
        forgedRole.andExpect(status().isForbidden())
        confirmVerified(memberships)
        cleared()
    }

    @Test
    fun `관리자의 파라미터가 잘못되었거나 날짜가 빠졌을 때__바인딩하면__로컬 빈 400과 전역 누락 파라미터 봉투를 유지한다`() {
        // given
        // when
        val badId = mvc.perform(get("$ADMIN/bad").header("Authorization", token("ROLE_ADMIN")))
        val badStatus = mvc.perform(get(ADMIN).param("status", "other").header("Authorization", token("ROLE_ADMIN")))
        val missingEnd = mvc.perform(
            get("$ADMIN/stats").param("startDate", "2026-10-20").header("Authorization", token("ROLE_ADMIN")),
        )

        // then
        badId
            .andExpect(status().isBadRequest())
            .andExpect(content().string(""))
        badStatus
            .andExpect(status().isBadRequest())
            .andExpect(content().string(""))
        missingEnd
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("필수 요청 파라미터 'endDate'(이)가 누락되었습니다."))
        confirmVerified(memberships)
    }

    @ParameterizedTest
    @ValueSource(strings = ["business", "database", "commit"])
    fun `업무 오류나 늦은 저장소 오류가 났을 때__승인하면__비밀과 쿠키와 컨텍스트 없이 안전한 원래 오류를 돌려준다`(
        phase: String,
    ) {
        // given
        val failure: RuntimeException = when (phase) {
            "business" -> ShelterMembershipRejectedException(ShelterMembershipRejectedException.Reason.CONFLICT)
            "database" -> DataAccessResourceFailureException("synthetic-private-secret")
            else -> TransactionSystemException("synthetic-private-secret")
        }

        every { memberships.approve(any(), any(), any(), any()) } throws failure

        // when
        val response = mvc.perform(
            post("$ADMIN/91/approve")
                .header("Authorization", token("ROLE_ADMIN"))
                .contentType("application/json")
                .content("{\"careRegNo\":\"reg\",\"note\":\"note\"}"),
        )
            .andExpect(status().`is`(if (phase == "business") 409 else 500))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andReturn().response

        // then
        assertThat(response.contentAsString).doesNotContain("synthetic-private-secret")
        assertThat(response.getHeader("Cache-Control")).contains("no-store")
        verify(exactly = 1) { memberships.approve(7, 91, "reg", "note") }
        cleared()
    }

    @Test
    fun `잘못된 요청 본문과 인접 경로가 주어졌을 때__호출하면__업무 호출 없이 거부한다`() {
        // given
        // when
        val blankName = mvc.perform(
            post(OWN)
                .header("Authorization", token("ROLE_USER"))
                .contentType("application/json")
                .content("{\"shelterName\":\" \"}"),
        )
        val blankReason = mvc.perform(
            post("$ADMIN/91/reject")
                .header("Authorization", token("ROLE_ADMIN"))
                .contentType("application/json")
                .content("{\"reason\":\" \"}"),
        )
        val wrongMethod = mvc.perform(put("$ADMIN/91/approve").header("Authorization", token("ROLE_ADMIN")))
        val extraPath = mvc.perform(get("$OWN/extra").header("Authorization", token("ROLE_USER")))

        // then
        blankName.andExpect(status().isBadRequest())
        blankReason.andExpect(status().isBadRequest())
        wrongMethod.andExpect(status().isForbidden())
        extraPath.andExpect(status().isForbidden())
        confirmVerified(memberships)
    }

    companion object {
        private const val OWN = "/api/v1/users/me/shelter-applications"
        private const val ADMIN = "/api/v1/admin/users/shelter-applications"

        private fun token(role: String): String = "Bearer " + F3TestInputs.token(7, role)

        private fun cleared() {
            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }

        private fun row(): ShelterMembership.Application {
            return ShelterMembership.Application(
                91L,
                8L,
                "보호소",
                ShelterMembership.Status.PENDING,
                LocalDateTime.parse("2026-10-20T01:02:03.123456"),
                null,
                null,
                null,
                null,
            )
        }
    }
}
