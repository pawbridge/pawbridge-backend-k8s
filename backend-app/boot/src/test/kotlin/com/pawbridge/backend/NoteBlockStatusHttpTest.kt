package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
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

    @MockitoBean
    lateinit var members: ContactMemberQuery

    @MockitoBean
    lateinit var blocks: NoteBlockStatusReader

    @MockitoBean
    lateinit var recipientBlocks: NoteBlockReader

    @Test
    fun given__verified_principal_and_spoofed_identity__when__reading_block_status__then__return_only_reader_ordered_raw_ids_and_query_only_current_member() {
        val largeId = Long.MAX_VALUE
        val distinctIds = listOf(largeId, 9L, 7L, 8L)
        whenever(members.get(7)).thenReturn(active(7))
        whenever(blocks.blockedAuthors(7, distinctIds)).thenReturn(listOf(8L, 9L, largeId))

        val response = mvc.perform(
            authenticatedStatus()
                .param("memberIds", "$largeId,9,7,8,9")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":999,\"memberIds\":[999]}"),
        )
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
        val order = inOrder(members, blocks)
        order.verify(members).get(7)
        order.verify(blocks).blockedAuthors(7, distinctIds)
        verifyNoMoreInteractions(members, blocks)
        verifyNoInteractions(recipientBlocks)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__no_matching_outgoing_blocks__when__reading__then__return_an_empty_array() {
        whenever(members.get(7)).thenReturn(active(7))
        whenever(blocks.blockedAuthors(7, listOf(8L))).thenReturn(listOf())

        mvc.perform(authenticatedStatus().param("memberIds", "8"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"memberIds\":[]}}",
                    JsonCompareMode.STRICT,
                ),
            )

        verify(members).get(7)
        verify(blocks).blockedAuthors(7, listOf(8L))
        verifyNoMoreInteractions(members, blocks)
        verifyNoInteractions(recipientBlocks)
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    fun given__missing_or_invalid_jwt_with_unconvertible_ids__when__reading__then__authenticate_before_binding_or_dependencies(
        authorization: String?,
        message: String,
    ) {
        val request = get("/api/v1/notes/blocks/status").param("memberIds", "abc").header("X-User-Id", "7")
        if (authorization != null) {
            request.header("Authorization", authorization)
        }

        mvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT),
            )

        verifyNoInteractions(members, blocks, recipientBlocks)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__authenticated_request_without_query_ids__when__binding__then__return_common_400_envelope_before_dependencies() {
        mvc.perform(authenticatedStatus().contentType(MediaType.APPLICATION_JSON).content("{\"memberIds\":[8]}"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"필수 요청 파라미터 'memberIds'(이)가 누락되었습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )

        verifyNoInteractions(members, blocks, recipientBlocks)
    }

    @Test
    fun given__authenticated_csv_mixed_with_repeated_parameter__when__binding__then__return_message_only_conversion_400_before_dependencies() {
        mvc.perform(authenticatedStatus().param("memberIds", "8,9", "10"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT))

        verifyNoInteractions(members, blocks, recipientBlocks)
    }

    @Test
    fun given__authenticated_empty_query_value__when__validating_bound_list__then__return_message_only_list_400_before_member_lookup() {
        mvc.perform(authenticatedStatus().param("memberIds", ""))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"작성자 식별자는 1~100개의 양수여야 합니다.\"}", JsonCompareMode.STRICT))

        verifyNoInteractions(members, blocks, recipientBlocks)
    }

    @Test
    fun given__pending_current_member__when__reading_valid_ids__then__return_message_only_403_without_block_query() {
        whenever(members.get(7)).thenReturn(ContactMember(7L, "탈퇴한 회원", false, true))

        mvc.perform(authenticatedStatus().param("memberIds", "8"))
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT))

        verify(members).get(7)
        verifyNoMoreInteractions(members)
        verifyNoInteractions(blocks, recipientBlocks)
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    fun given__user_or_block_dependency_failure__when__reading_valid_ids__then__preserve_source_specific_message_only_503(
        source: String,
        failure: RuntimeException,
        message: String,
    ) {
        if (source == "user") {
            whenever(members.get(7)).thenThrow(failure)
        } else {
            whenever(members.get(7)).thenReturn(active(7))
            whenever(blocks.blockedAuthors(7, listOf(8L))).thenThrow(failure)
        }

        mvc.perform(authenticatedStatus().param("memberIds", "8"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to message)), JsonCompareMode.STRICT))

        verify(members).get(7)
        if (source == "user") {
            verifyNoInteractions(blocks)
        } else {
            verify(blocks).blockedAuthors(7, listOf(8L))
        }
        verifyNoMoreInteractions(members, blocks)
        verifyNoInteractions(recipientBlocks)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/notes/stream/extra", "GET /api/v1/notes/blocks/status/", "GET /api/notes/blocks/status",
            "HEAD /api/v1/notes/blocks/status", "OPTIONS /api/v1/notes/blocks/status", "POST /api/v1/notes/blocks/status",
        ],
    )
    fun given__unexposed_neighbor_path_or_method__when__requesting_with_a_valid_token__then__deny_before_dependencies(
        request: String,
    ) {
        val parts = request.split(" ", limit = 2)

        mvc.perform(
            request(HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())

        verifyNoInteractions(members, blocks, recipientBlocks)
    }

    @Test
    fun given__existing_recipient_route__when__its_own_rejection_occurs__then__retain_recipient_advice() {
        whenever(members.get(7)).thenReturn(active(7))

        mvc.perform(
            get("/api/v1/notes/recipients/7").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"이 상대에게 쪽지를 보낼 수 없습니다.\"}", JsonCompareMode.STRICT))

        verify(members).get(7)
        verifyNoMoreInteractions(members)
        verifyNoInteractions(blocks, recipientBlocks)
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
