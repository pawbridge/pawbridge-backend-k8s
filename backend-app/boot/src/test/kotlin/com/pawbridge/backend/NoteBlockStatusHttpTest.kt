package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader
import com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader
import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors
import com.pawbridge.backend.community.internal.note.application.ReadNoteRecipient
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusController
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteRecipientController
import com.pawbridge.backend.community.internal.note.web.NoteRecipientExceptionHandler
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
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
import org.springframework.http.HttpMethod
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
import java.util.stream.Stream

@WebMvcTest(
    controllers = [NoteBlockStatusController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        NoteBlockStatusController::class,
        NoteBlockStatusExceptionHandler::class,
        ReadBlockedAuthors::class,
        NoteRecipientController::class,
        NoteRecipientExceptionHandler::class,
        ReadNoteRecipient::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class NoteBlockStatusHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockkBean
    lateinit var members: ContactMemberQuery

    @MockkBean
    lateinit var blocks: NoteBlockStatusReader

    @MockkBean
    lateinit var recipientBlocks: NoteBlockReader

    @Test
    fun `검증된 주체와 위조 신원이 주어졌을 때__차단 상태를 읽으면__현재 회원만 조회하고 원본 ID를 순서대로 준다`() {
        // given
        val largeId = Long.MAX_VALUE
        val distinctIds = listOf(largeId, 9L, 7L, 8L)

        every { members.get(any()) } returns active(7)
        every { blocks.blockedAuthors(any(), any()) } returns listOf(8L, 9L, largeId)

        // when
        val result = mvc.perform(
            authenticatedStatus()
                .param("memberIds", "$largeId,9,7,8,9")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":999,\"memberIds\":[999]}"),
        )

        // then
        val response = result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"memberIds\":[8,9," +
                        largeId + "]}}",
                    JsonCompareMode.STRICT,
                ),
            )
            .andReturn().response
        val rawId = mapper.readTree(response.contentAsString).path("data").path("memberIds").get(2)
        assertThat(rawId.isIntegralNumber()).isTrue()
        assertThat(rawId.longValue()).isEqualTo(largeId)
        verifySequence {
            members.get(7)
            blocks.blockedAuthors(7, distinctIds)
        }
        confirmVerified(members, blocks, recipientBlocks)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `일치하는 보낸 차단이 없을 때__읽으면__빈 배열을 돌려준다`() {
        // given
        every { members.get(any()) } returns active(7)
        every { blocks.blockedAuthors(any(), any()) } returns listOf()

        // when
        val result = mvc.perform(authenticatedStatus().param("memberIds", "8"))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"memberIds\":[]}}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { members.get(7) }
        verify(exactly = 1) { blocks.blockedAuthors(7, listOf(8L)) }
        confirmVerified(members, blocks, recipientBlocks)
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    fun `토큰이 없거나 잘못되고 ID를 변환할 수 없을 때__읽으면__바인딩과 의존성 전에 인증 실패를 준다`(
        authorization: String?,
        message: String,
    ) {
        // given
        val request = get("/api/v1/notes/blocks/status").param("memberIds", "abc").header("X-User-Id", "7")
        if (authorization != null) {
            request.header("Authorization", authorization)
        }

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(members, blocks, recipientBlocks)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `쿼리에 ID가 없는 인증 요청일 때__바인딩하면__의존성 전에 공통 400 응답을 준다`() {
        // given
        val request = authenticatedStatus().contentType(MediaType.APPLICATION_JSON).content("{\"memberIds\":[8]}")

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"필수 요청 파라미터 'memberIds'(이)가 누락되었습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        confirmVerified(members, blocks, recipientBlocks)
    }

    @Test
    fun `쉼표 목록과 반복 파라미터가 섞였을 때__바인딩하면__의존성 전에 변환 오류 400을 준다`() {
        // given
        val request = authenticatedStatus().param("memberIds", "8,9", "10")

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT))
        confirmVerified(members, blocks, recipientBlocks)
    }

    @Test
    fun `쿼리 값이 비어 있을 때__목록을 검증하면__회원 조회 전에 목록 400을 준다`() {
        // given
        val request = authenticatedStatus().param("memberIds", "")

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"작성자 식별자는 1~100개의 양수여야 합니다.\"}", JsonCompareMode.STRICT))
        confirmVerified(members, blocks, recipientBlocks)
    }

    @Test
    fun `탈퇴 대기 호출자가 주어졌을 때__유효한 ID를 읽으면__차단 조회 없이 메시지만 담은 403을 준다`() {
        // given
        every { members.get(any()) } returns ContactMember(7L, "탈퇴한 회원", false, true)

        // when
        val result = mvc.perform(authenticatedStatus().param("memberIds", "8"))

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT))
        verify(exactly = 1) { members.get(7) }
        confirmVerified(members, blocks, recipientBlocks)
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    fun `회원이나 차단 조회가 실패했을 때__유효한 ID를 읽으면__원인별 메시지의 503을 유지한다`(
        source: String,
        failure: RuntimeException,
        message: String,
    ) {
        // given
        if (source == "user") {
            every { members.get(any()) } throws failure
        } else {
            every { members.get(any()) } returns active(7)
            every { blocks.blockedAuthors(any(), any()) } throws failure
        }

        // when
        val result = mvc.perform(authenticatedStatus().param("memberIds", "8"))

        // then
        result
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to message)), JsonCompareMode.STRICT))
        verify(exactly = 1) { members.get(7) }
        if (source != "user") {
            verify(exactly = 1) { blocks.blockedAuthors(7, listOf(8L)) }
        }
        confirmVerified(members, blocks, recipientBlocks)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/notes/stream/extra", "GET /api/v1/notes/blocks/status/", "GET /api/notes/blocks/status",
            "HEAD /api/v1/notes/blocks/status", "OPTIONS /api/v1/notes/blocks/status", "POST /api/v1/notes/blocks/status",
        ],
    )
    fun `노출되지 않은 인접 경로나 메서드일 때__유효한 토큰으로 요청하면__의존성 전에 거부한다`(
        request: String,
    ) {
        // given
        val parts = request.split(" ", limit = 2)

        // when
        val result = mvc.perform(
            request(HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(members, blocks, recipientBlocks)
    }

    @Test
    fun `기존 수신자 경로일 때__자체 거절이 나면__수신자 어드바이스를 유지한다`() {
        // given
        every { members.get(any()) } returns active(7)

        // when
        val result = mvc.perform(
            get("/api/v1/notes/recipients/7").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"이 상대에게 쪽지를 보낼 수 없습니다.\"}", JsonCompareMode.STRICT))
        verify(exactly = 1) { members.get(7) }
        confirmVerified(members, blocks, recipientBlocks)
    }

    private fun authenticatedStatus(): MockHttpServletRequestBuilder {
        return get("/api/v1/notes/blocks/status")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
    }

    companion object {
        @JvmStatic
        fun rejectedAuthorizations(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."),
            )
        }

        @JvmStatic
        fun dependencyFailures(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    "user",
                    ContactMemberUnavailableException(DataAccessResourceFailureException("synthetic-private-user-query")),
                    "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.",
                ),
                Arguments.of(
                    "block",
                    DataAccessResourceFailureException("synthetic-private-block-query", SQLException("synthetic-private-password")),
                    "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.",
                ),
                Arguments.of(
                    "block",
                    IllegalStateException("Bearer synthetic-private-token"),
                    "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                ),
                Arguments.of(
                    "BLOCK_BEGIN",
                    CannotCreateTransactionException("synthetic-private-begin"),
                    "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.",
                ),
                Arguments.of(
                    "BLOCK_COMMIT",
                    TransactionSystemException("synthetic-private-commit"),
                    "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.",
                ),
                Arguments.of(
                    "BLOCK_TIMEOUT",
                    TransactionTimedOutException("synthetic-private-timeout"),
                    "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.",
                ),
                Arguments.of(
                    "USAGE",
                    IllegalTransactionStateException("synthetic-private-usage"),
                    "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                ),
            )
        }

        private fun active(id: Long): ContactMember = ContactMember(id, "합성 회원", true, false)
    }
}
