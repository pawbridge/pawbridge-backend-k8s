package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader
import com.pawbridge.backend.community.internal.note.application.ReadNoteRecipient
import com.pawbridge.backend.community.internal.note.web.NoteRecipientController
import com.pawbridge.backend.community.internal.note.web.NoteRecipientExceptionHandler
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import com.pawbridge.backend.user.internal.favorite.application.FavoriteStatusQuery
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusController
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusExceptionHandler
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
import java.util.stream.Stream

@WebMvcTest(
    controllers = [NoteRecipientController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        NoteRecipientController::class,
        NoteRecipientExceptionHandler::class,
        ReadNoteRecipient::class,
        FavoriteStatusController::class,
        FavoriteStatusExceptionHandler::class,
        PostReadController::class,
        PostReadExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class NoteRecipientHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockkBean
    lateinit var members: ContactMemberQuery

    @MockkBean
    lateinit var blocks: NoteBlockReader

    @MockkBean
    lateinit var favorites: FavoriteStatusQuery

    @MockkBean
    lateinit var readPost: ReadPost

    @MockkBean
    lateinit var listPosts: ListPosts

    @Test
    fun `검증된 주체와 위조 신원이 주어졌을 때__수신자를 읽으면__경로 회원의 원본 필드 네 개만 돌려준다`() {
        // given
        val target = Long.MAX_VALUE
        val recipient = ContactMember(target, "  ", true, false)

        every { members.get(7) } returns active(7)
        every { members.get(target) } returns recipient
        every { blocks.blocked(any(), any()) } returns false

        // when
        val result = mvc.perform(
            authenticatedRecipient(target)
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "999")
                .param("member", "999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":999,\"member\":999}"),
        )

        // then
        val response = result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"userId\":" + target +
                        ",\"nickname\":\"  \",\"active\":true,\"deletionPending\":false}}",
                    JsonCompareMode.STRICT,
                ),
            )
            .andReturn().response
        val id = mapper.readTree(response.contentAsString).path("data").path("userId")
        assertThat(id.isIntegralNumber()).isTrue()
        assertThat(id.longValue()).isEqualTo(target)
        verifySequence {
            members.get(7)
            blocks.blocked(7, target)
            members.get(target)
        }
        confirmVerified(members, blocks, favorites, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "9223372036854775808"])
    fun `검증된 토큰과 깨지거나 넘치는 회원 경로일 때__바인딩하면__의존성 전에 메시지만 담은 400을 준다`(
        target: String,
    ) {
        // given
        val request = get("/api/v1/notes/recipients/{member}", target)
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    mapper.writeValueAsString(mapOf("message" to "요청 형식과 로그인 정보를 확인해 주세요.")),
                    JsonCompareMode.STRICT,
                ),
            )
        confirmVerified(members, blocks, favorites, readPost, listPosts)
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `활성 호출자와 양수가 아닌 대상이 주어졌을 때__읽으면__차단 조회 뒤 메시지만 담은 401을 준다`(
        target: Long,
    ) {
        // given
        every { members.get(any()) } returns active(7)
        every { blocks.blocked(any(), any()) } returns false

        // when
        val result = mvc.perform(authenticatedRecipient(target))

        // then
        result
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"로그인이 필요합니다.\"}", JsonCompareMode.STRICT))
        verifySequence {
            members.get(7)
            blocks.blocked(7, target)
        }
        confirmVerified(members, blocks, favorites, readPost, listPosts)
    }

    @ParameterizedTest
    @MethodSource("rejectedRecipients")
    fun `자기 자신 차단 비활성 대상이 주어졌을 때__읽으면__원래 메시지의 403을 주고 멈춘다`(
        scenario: String,
        target: Long,
        message: String,
    ) {
        // given
        every { members.get(7) } returns
            if (scenario == "current-pending") ContactMember(7L, "탈퇴한 회원", false, true) else active(7)
        every { blocks.blocked(any(), any()) } returns (scenario == "blocked")
        if (scenario == "target-missing") {
            every { members.get(target) } returns ContactMember(target, "탈퇴한 회원", false, false)
        }

        // when
        val result = mvc.perform(authenticatedRecipient(target))

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to message)), JsonCompareMode.STRICT))
        verify(exactly = 1) { members.get(7) }
        if (scenario != "self" && scenario != "current-pending") {
            verify(exactly = 1) { blocks.blocked(7, target) }
        }
        if (scenario == "target-missing") {
            verify(exactly = 1) { members.get(target) }
        }
        confirmVerified(members, blocks, favorites, readPost, listPosts)
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    fun `유효하지 않은 대상 전에 회원이나 차단 조회가 실패했을 때__읽으면__원인별 안전한 503을 유지한다`(
        source: String,
        failure: RuntimeException,
        message: String,
    ) {
        // given
        if (source == "user") {
            every { members.get(any()) } throws failure
        } else {
            every { members.get(any()) } returns active(7)
            every { blocks.blocked(any(), any()) } throws failure
        }

        // when
        val result = mvc.perform(authenticatedRecipient(0))

        // then
        result
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to message)), JsonCompareMode.STRICT))
        verify(exactly = 1) { members.get(7) }
        if (source != "user") {
            verify(exactly = 1) { blocks.blocked(7, 0) }
        }
        confirmVerified(members, blocks, favorites, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `기존 즐겨찾기와 공개 게시글 경로일 때__읽으면__인증과 범위 실패 계약을 유지한다`() {
        // given
        every { readPost.read(any()) } throws IllegalStateException("synthetic-private-post")

        // when
        val favorite = mvc.perform(get("/api/v1/favorites/42/check"))
        val post = mvc.perform(get("/api/v1/posts/read/42").header("Authorization", "Bearer invalid"))

        // then
        favorite.andExpect(status().isUnauthorized())
        post
            .andExpect(status().isInternalServerError())
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        verify(exactly = 1) { readPost.read(42) }
        confirmVerified(members, blocks, favorites, readPost, listPosts)
    }

    private fun authenticatedRecipient(target: Long): MockHttpServletRequestBuilder {
        return get("/api/v1/notes/recipients/{member}", target)
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
        fun rejectedRecipients(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("self", 7L, "이 상대에게 쪽지를 보낼 수 없습니다."),
                Arguments.of("blocked", 8L, "이 상대에게 쪽지를 보낼 수 없습니다."),
                Arguments.of("current-pending", 8L, "이용할 수 없는 회원입니다."),
                Arguments.of("target-missing", 8L, "이용할 수 없는 회원입니다."),
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
