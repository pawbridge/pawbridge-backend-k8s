package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.note.application.ListNoteBlocks
import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader
import com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader
import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors
import com.pawbridge.backend.community.internal.note.application.ReadNoteRecipient
import com.pawbridge.backend.community.internal.note.web.NoteBlockListController
import com.pawbridge.backend.community.internal.note.web.NoteBlockListExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusController
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteRecipientController
import com.pawbridge.backend.community.internal.note.web.NoteRecipientExceptionHandler
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import com.pawbridge.backend.user.api.ContactMembersQuery
import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import io.mockk.verifySequence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.TransactionTimedOutException
import java.sql.SQLException
import java.time.Instant
import java.util.stream.Stream

@WebMvcTest(
    controllers = [NoteBlockListController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        NoteBlockListController::class,
        NoteBlockListExceptionHandler::class,
        ListNoteBlocks::class,
        NoteRecipientController::class,
        NoteRecipientExceptionHandler::class,
        ReadNoteRecipient::class,
        NoteBlockStatusController::class,
        NoteBlockStatusExceptionHandler::class,
        ReadBlockedAuthors::class,
        TotalUserCountController::class,
        TotalUserCountExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class NoteBlockListHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockkBean
    lateinit var members: ContactMemberQuery

    @MockkBean
    lateinit var batches: ContactMembersQuery

    @MockkBean
    lateinit var blocks: NoteBlockListReader

    @MockkBean
    lateinit var recipientBlocks: NoteBlockReader

    @MockkBean
    lateinit var statusBlocks: NoteBlockStatusReader

    @MockkBean
    lateinit var counts: TotalUserCountQuery

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun `일반 역할과 위조 신원이 주어졌을 때__기본 페이지를 읽으면__한 번의 배치로 정확한 페이지를 준다`(
        role: String,
    ) {
        // given
        every { members.get(any()) } returns active(7)
        every { blocks.count(any()) } returns 11L
        every { blocks.read(any(), any()) } returns listOf(
            NoteBlockListReader.Block(Long.MAX_VALUE, CREATED_AT),
            NoteBlockListReader.Block(8, CREATED_AT.minusNanos(1000)),
        )
        every { batches.getAll(any()) } returns listOf(
            ContactMember(8L, "원래 닉네임을 노출하지 않음", false, true),
            ContactMember(Long.MAX_VALUE, "  ", true, false),
        )

        // when
        val result = mvc.perform(
            authenticatedList(role)
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "999")
                .param("role", "ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":999,\"page\":9}"),
        )

        // then
        val response = result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{" +
                        "\"content\":[{\"memberId\":" + Long.MAX_VALUE +
                        ",\"nickname\":\"  \",\"createdAt\":\"2026-10-19T15:00:00.123456Z\"}," +
                        "{\"memberId\":8,\"nickname\":\"탈퇴한 회원\",\"createdAt\":\"2026-10-19T15:00:00.123455Z\"}]," +
                        "\"totalElements\":11,\"totalPages\":2,\"number\":0}}",
                    JsonCompareMode.STRICT,
                ),
            )
            .andReturn().response
        val rawId = mapper.readTree(response.contentAsString).path("data").path("content").get(0).path("memberId")
        assertThat(rawId.isIntegralNumber()).isTrue()
        assertThat(rawId.longValue()).isEqualTo(Long.MAX_VALUE)
        verifySequence {
            members.get(7)
            blocks.count(7)
            blocks.read(7, 0)
            batches.getAll(listOf(Long.MAX_VALUE, 8L))
        }
        confirmVerified(members, blocks, batches, recipientBlocks, statusBlocks, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `최대 페이지 번호의 빈 페이지일 때__읽으면__배치 조회 없이 총계와 요청 페이지를 유지한다`() {
        // given
        every { members.get(any()) } returns active(7)
        every { blocks.count(any()) } returns 11L
        every { blocks.read(any(), any()) } returns listOf()

        // when
        val result = mvc.perform(authenticatedList("ROLE_USER").param("page", "10000"))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{" +
                        "\"content\":[],\"totalElements\":11,\"totalPages\":2,\"number\":10000}}",
                    JsonCompareMode.STRICT,
                ),
            )
        verifySequence {
            members.get(7)
            blocks.count(7)
            blocks.read(7, 10000)
        }
        confirmVerified(members, blocks, batches, recipientBlocks, statusBlocks, counts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "2147483648"])
    fun `변환할 수 없는 페이지가 주어졌을 때__바인딩하면__회원 조회 전에 메시지만 담은 400을 준다`(
        page: String,
    ) {
        // given
        val request = authenticatedList("ROLE_USER").param("page", page)

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT))
        confirmVerified(members, batches, blocks, recipientBlocks, statusBlocks, counts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["-1", "10001"])
    fun `범위를 벗어난 페이지가 주어졌을 때__검증하면__회원 의존성 전에 페이지 400을 준다`(
        page: String,
    ) {
        // given
        val request = authenticatedList("ROLE_USER").param("page", page)

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"페이지 범위를 확인해 주세요.\"}", JsonCompareMode.STRICT))
        confirmVerified(members, batches, blocks, recipientBlocks, statusBlocks, counts)
    }

    @Test
    fun `탈퇴 대기 호출자가 주어졌을 때__유효한 페이지를 읽으면__집계 전에 이용 불가 403을 준다`() {
        // given
        every { members.get(any()) } returns ContactMember(7L, "탈퇴한 회원", false, true)

        // when
        val result = mvc.perform(authenticatedList("ROLE_USER").param("page", "0"))

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT))
        verify(exactly = 1) { members.get(7) }
        confirmVerified(members, batches, blocks, recipientBlocks, statusBlocks, counts)
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    fun `호출자 배치 차단 조회가 실패했을 때__유효한 페이지를 읽으면__단계별 메시지의 503을 주고 멈춘다`(
        phase: String,
        failure: RuntimeException,
        message: String,
    ) {
        // given
        if (phase == "CURRENT") {
            every { members.get(any()) } throws failure
        } else {
            every { members.get(any()) } returns active(7)
            if (phase == "COUNT_DAO") {
                every { blocks.count(any()) } throws failure
            } else {
                every { blocks.count(any()) } returns 1L
                if (phase == "BATCH") {
                    every { blocks.read(any(), any()) } returns listOf(NoteBlockListReader.Block(8, CREATED_AT))
                    every { batches.getAll(any()) } throws failure
                } else {
                    every { blocks.read(any(), any()) } throws failure
                }
            }
        }

        // when
        val result = mvc.perform(authenticatedList("ROLE_USER").param("page", "0"))

        // then
        result
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to message)), JsonCompareMode.STRICT))
        verify(exactly = 1) { members.get(7) }
        if (phase != "CURRENT") {
            verify(exactly = 1) { blocks.count(7) }
            if (phase != "COUNT_DAO") {
                verify(exactly = 1) { blocks.read(7, 0) }
            }
            if (phase == "BATCH") {
                verify(exactly = 1) { batches.getAll(listOf(8L)) }
            }
        }
        confirmVerified(members, blocks, batches, recipientBlocks, statusBlocks, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `기존 수신자 상태 관리자 경로가 주어졌을 때__각자 거절하면__차단 목록 조회 없이 범위 정책을 유지한다`() {
        // given
        every { members.get(any()) } returns active(7)

        // when
        val recipient = mvc.perform(
            get("/api/v1/notes/recipients/7").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
        val statusResult = mvc.perform(
            get("/api/v1/notes/blocks/status")
                .param("memberIds", "0")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
        val admin = mvc.perform(
            get("/api/v1/admin/users/stats/total-users")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        recipient
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json("{\"message\":\"이 상대에게 쪽지를 보낼 수 없습니다.\"}", JsonCompareMode.STRICT))
        statusResult
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json("{\"message\":\"작성자 식별자는 1~100개의 양수여야 합니다.\"}", JsonCompareMode.STRICT))
        admin
            .andExpect(status().isForbidden())
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(exactly = 1) { members.get(7) }
        confirmVerified(members, batches, blocks, recipientBlocks, statusBlocks, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    private fun authenticatedList(role: String): MockHttpServletRequestBuilder {
        return get(PATH).header("Authorization", "Bearer " + F3TestInputs.token(7, role))
    }

    companion object {
        private const val PATH = "/api/v1/notes/blocks"
        private val CREATED_AT: Instant = Instant.parse("2026-10-19T15:00:00.123456Z")

        @JvmStatic
        fun rejectedAuthorizations(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."),
            )
        }

        @JvmStatic
        fun dependencyFailures(): Stream<Arguments> {
            val member = "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
            val storage = "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."
            val generic = "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."
            return Stream.of(
                Arguments.of(
                    "CURRENT",
                    ContactMemberUnavailableException(DataAccessResourceFailureException("synthetic-private-caller")),
                    member,
                ),
                Arguments.of(
                    "BATCH",
                    ContactMemberUnavailableException(TransactionSystemException("synthetic-private-batch-commit")),
                    member,
                ),
                Arguments.of("COUNT_DAO", DataAccessResourceFailureException("synthetic-private-count"), storage),
                Arguments.of(
                    "LIST_DAO",
                    DataAccessResourceFailureException("synthetic-private-list", SQLException("synthetic-private-password")),
                    storage,
                ),
                Arguments.of("BLOCK_BEGIN", CannotCreateTransactionException("synthetic-private-begin"), storage),
                Arguments.of("BLOCK_COMMIT", TransactionSystemException("synthetic-private-commit"), storage),
                Arguments.of("BLOCK_TIMEOUT", TransactionTimedOutException("synthetic-private-timeout"), storage),
                Arguments.of("USAGE", IllegalTransactionStateException("synthetic-private-usage"), generic),
                Arguments.of("RUNTIME", IllegalStateException("Bearer synthetic-private-token"), generic),
            )
        }

        private fun active(id: Long): ContactMember = ContactMember(id, "본인", true, false)
    }
}
