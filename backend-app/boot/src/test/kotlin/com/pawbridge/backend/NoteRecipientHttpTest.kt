package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
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

    @MockitoBean
    lateinit var members: ContactMemberQuery

    @MockitoBean
    lateinit var blocks: NoteBlockReader

    @MockitoBean
    lateinit var favorites: FavoriteStatusQuery

    @MockitoBean
    lateinit var readPost: ReadPost

    @MockitoBean
    lateinit var listPosts: ListPosts

    @Test
    fun given__verified_principal_and_spoofed_identity__when__reading_recipient__then__return_only_four_raw_fields_and_use_the_path_member() {
        val target = Long.MAX_VALUE
        val recipient = ContactMember(target, "  ", true, false)
        whenever(members.get(7)).thenReturn(active(7))
        whenever(members.get(target)).thenReturn(recipient)

        val response = mvc.perform(
            authenticatedRecipient(target)
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "999")
                .param("member", "999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":999,\"member\":999}"),
        )
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
        val order = inOrder(members, blocks)
        order.verify(members).get(7)
        order.verify(blocks).blocked(7, target)
        order.verify(members).get(target)
        verifyNoMoreInteractions(members, blocks)
        verifyNoInteractions(favorites, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    fun given__missing_or_invalid_jwt_with_malformed_member__when__reading__then__authentication_precedes_binding_and_dependencies(
        authorization: String?,
        message: String,
    ) {
        val request = get("/api/v1/notes/recipients/abc").header("X-User-Id", "7")
        if (authorization != null) {
            request.header("Authorization", authorization)
        }

        mvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    mapper.writeValueAsString(NoteRecipientController.Response<Void?>(401, message, null)),
                    JsonCompareMode.STRICT,
                ),
            )

        verifyNoInteractions(members, blocks, favorites, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "9223372036854775808"])
    fun given__verified_jwt_and_malformed_or_overflow_member__when__binding__then__return_message_only_400_before_dependencies(
        target: String,
    ) {
        mvc.perform(
            get("/api/v1/notes/recipients/{member}", target)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    mapper.writeValueAsString(mapOf("message" to "요청 형식과 로그인 정보를 확인해 주세요.")),
                    JsonCompareMode.STRICT,
                ),
            )

        verifyNoInteractions(members, blocks, favorites, readPost, listPosts)
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun given__nonpositive_target_after_active_caller__when__reading__then__block_query_precedes_message_only_401(
        target: Long,
    ) {
        whenever(members.get(7)).thenReturn(active(7))

        mvc.perform(authenticatedRecipient(target))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"로그인이 필요합니다.\"}", JsonCompareMode.STRICT))

        val order = inOrder(members, blocks)
        order.verify(members).get(7)
        order.verify(blocks).blocked(7, target)
        verifyNoMoreInteractions(members, blocks)
        verifyNoInteractions(favorites, readPost, listPosts)
    }

    @ParameterizedTest
    @MethodSource("rejectedRecipients")
    fun given__self_block_or_inactive_member__when__reading__then__return_the_original_message_only_403_and_stop(
        scenario: String,
        target: Long,
        message: String,
    ) {
        whenever(members.get(7)).thenReturn(
            if (scenario == "current-pending") ContactMember(7L, "탈퇴한 회원", false, true) else active(7),
        )
        if (scenario == "blocked") {
            whenever(blocks.blocked(7, target)).thenReturn(true)
        }
        if (scenario == "target-missing") {
            whenever(members.get(target)).thenReturn(ContactMember(target, "탈퇴한 회원", false, false))
        }

        mvc.perform(authenticatedRecipient(target))
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to message)), JsonCompareMode.STRICT))

        verify(members).get(7)
        if (scenario == "self" || scenario == "current-pending") {
            verifyNoInteractions(blocks)
        } else {
            verify(blocks).blocked(7, target)
        }
        if (scenario == "target-missing") {
            verify(members).get(target)
        }
        verifyNoMoreInteractions(members, blocks)
        verifyNoInteractions(favorites, readPost, listPosts)
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    fun given__user_or_block_failure_before_invalid_target__when__reading__then__preserve_source_specific_safe_503(
        source: String,
        failure: RuntimeException,
        message: String,
    ) {
        if (source == "user") {
            whenever(members.get(7)).thenThrow(failure)
        } else {
            whenever(members.get(7)).thenReturn(active(7))
            whenever(blocks.blocked(7, 0)).thenThrow(failure)
        }

        mvc.perform(authenticatedRecipient(0))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to message)), JsonCompareMode.STRICT))

        verify(members).get(7)
        if (source == "user") {
            verifyNoInteractions(blocks)
        } else {
            verify(blocks).blocked(7, 0)
        }
        verifyNoMoreInteractions(members, blocks)
        verifyNoInteractions(favorites, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/notes/stream/extra",
            "GET /api/v1/chats", "GET /api/v1/users/internal/8/contact", "DELETE /internal/private-notes/members/8",
            "GET /api/notes/recipients/8", "GET /api/v1/notes/recipients/8/", "HEAD /api/v1/notes/recipients/8",
            "OPTIONS /api/v1/notes/recipients/8", "POST /api/v1/notes/recipients/8", "PUT /api/v1/notes/recipients/8",
            "DELETE /api/v1/notes/recipients/8",
        ],
    )
    fun given__unexposed_note_chat_internal_path_or_method__when__requesting_with_a_valid_token__then__deny_before_dependencies(
        request: String,
    ) {
        val parts = request.split(" ", limit = 2)

        mvc.perform(
            request(HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())

        verifyNoInteractions(members, blocks, favorites, readPost, listPosts)
    }

    @Test
    fun given__existing_favorite_and_public_post_paths__when__reading__then__keep_authentication_and_scoped_failure_contracts() {
        whenever(readPost.read(42)).thenThrow(IllegalStateException("synthetic-private-post"))

        mvc.perform(get("/api/v1/favorites/42/check")).andExpect(status().isUnauthorized())
        mvc.perform(get("/api/v1/posts/read/42").header("Authorization", "Bearer invalid"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))

        verify(readPost).read(42)
        verifyNoInteractions(members, blocks, favorites, listPosts)
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
