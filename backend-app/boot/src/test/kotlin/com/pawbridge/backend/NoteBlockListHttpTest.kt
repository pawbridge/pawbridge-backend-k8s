package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
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

    @MockitoBean
    lateinit var members: ContactMemberQuery

    @MockitoBean
    lateinit var batches: ContactMembersQuery

    @MockitoBean
    lateinit var blocks: NoteBlockListReader

    @MockitoBean
    lateinit var recipientBlocks: NoteBlockReader

    @MockitoBean
    lateinit var statusBlocks: NoteBlockStatusReader

    @MockitoBean
    lateinit var counts: TotalUserCountQuery

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun given__ordinary_authenticated_role_and_spoofed_identity__when__reading_default_page__then__return_exact_page_and_raw_ids_instants_after_one_batch(
        role: String,
    ) {
        whenever(members.get(7)).thenReturn(active(7))
        whenever(blocks.count(7)).thenReturn(11L)
        whenever(blocks.read(7, 0)).thenReturn(
            listOf(
                NoteBlockListReader.Block(Long.MAX_VALUE, CREATED_AT),
                NoteBlockListReader.Block(8, CREATED_AT.minusNanos(1000)),
            ),
        )
        whenever(batches.getAll(listOf(Long.MAX_VALUE, 8L))).thenReturn(
            listOf(
                ContactMember(8L, "원래 닉네임을 노출하지 않음", false, true),
                ContactMember(Long.MAX_VALUE, "  ", true, false),
            ),
        )

        val response = mvc.perform(
            authenticatedList(role)
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "999")
                .param("role", "ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":999,\"page\":9}"),
        )
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
        val order = inOrder(members, blocks, batches)
        order.verify(members).get(7)
        order.verify(blocks).count(7)
        order.verify(blocks).read(7, 0)
        order.verify(batches).getAll(listOf(Long.MAX_VALUE, 8L))
        verifyNoMoreInteractions(members, blocks, batches)
        verifyNoInteractions(recipientBlocks, statusBlocks, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__empty_page_at_maximum_page_number__when__reading__then__retain_total_and_requested_page_without_batch_lookup() {
        whenever(members.get(7)).thenReturn(active(7))
        whenever(blocks.count(7)).thenReturn(11L)
        whenever(blocks.read(7, 10000)).thenReturn(listOf())

        mvc.perform(authenticatedList("ROLE_USER").param("page", "10000"))
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

        val order = inOrder(members, blocks)
        order.verify(members).get(7)
        order.verify(blocks).count(7)
        order.verify(blocks).read(7, 10000)
        verifyNoMoreInteractions(members, blocks)
        verifyNoInteractions(batches, recipientBlocks, statusBlocks, counts)
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    fun given__missing_or_invalid_jwt_with_malformed_page__when__reading__then__authenticate_before_binding_and_all_dependencies(
        authorization: String?,
        message: String,
    ) {
        val request = get(PATH).param("page", "abc").header("X-User-Id", "7")
        if (authorization != null) {
            request.header("Authorization", authorization)
        }

        mvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    mapper.writeValueAsString(NoteBlockListController.Response<Void?>(401, message, null)),
                    JsonCompareMode.STRICT,
                ),
            )

        verifyNoInteractions(members, batches, blocks, recipientBlocks, statusBlocks, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "2147483648"])
    fun given__authenticated_unconvertible_page__when__binding__then__return_message_only_400_before_member_lookup(
        page: String,
    ) {
        mvc.perform(authenticatedList("ROLE_USER").param("page", page))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT))

        verifyNoInteractions(members, batches, blocks, recipientBlocks, statusBlocks, counts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["-1", "10001"])
    fun given__authenticated_out_of_range_page__when__validating__then__return_page_400_before_any_member_dependency(
        page: String,
    ) {
        mvc.perform(authenticatedList("ROLE_USER").param("page", page))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"페이지 범위를 확인해 주세요.\"}", JsonCompareMode.STRICT))

        verifyNoInteractions(members, batches, blocks, recipientBlocks, statusBlocks, counts)
    }

    @Test
    fun given__pending_current_member__when__reading_a_valid_page__then__return_inactive_403_before_count_page_or_batch() {
        whenever(members.get(7)).thenReturn(ContactMember(7L, "탈퇴한 회원", false, true))

        mvc.perform(authenticatedList("ROLE_USER").param("page", "0"))
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT))

        verify(members).get(7)
        verifyNoMoreInteractions(members)
        verifyNoInteractions(batches, blocks, recipientBlocks, statusBlocks, counts)
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    fun given__caller_batch_or_block_failure__when__reading_a_valid_page__then__preserve_phase_specific_message_only_503_and_stop(
        phase: String,
        failure: RuntimeException,
        message: String,
    ) {
        if (phase == "CURRENT") {
            whenever(members.get(7)).thenThrow(failure)
        } else {
            whenever(members.get(7)).thenReturn(active(7))
            if (phase == "COUNT_DAO") {
                whenever(blocks.count(7)).thenThrow(failure)
            } else {
                whenever(blocks.count(7)).thenReturn(1L)
                if (phase == "BATCH") {
                    whenever(blocks.read(7, 0)).thenReturn(listOf(NoteBlockListReader.Block(8, CREATED_AT)))
                    whenever(batches.getAll(listOf(8L))).thenThrow(failure)
                } else {
                    whenever(blocks.read(7, 0)).thenThrow(failure)
                }
            }
        }

        mvc.perform(authenticatedList("ROLE_USER").param("page", "0"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to message)), JsonCompareMode.STRICT))

        verify(members).get(7)
        if (phase == "CURRENT") {
            verifyNoInteractions(blocks, batches)
        } else {
            verify(blocks).count(7)
            if (phase != "COUNT_DAO") {
                verify(blocks).read(7, 0)
            }
            if (phase == "BATCH") {
                verify(batches).getAll(listOf(8L))
            } else {
                verifyNoInteractions(batches)
            }
        }
        verifyNoMoreInteractions(members, blocks, batches)
        verifyNoInteractions(recipientBlocks, statusBlocks, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/notes/stream/extra", "GET /api/v1/notes/blocks/", "GET /api/notes/blocks",
            "HEAD /api/v1/notes/blocks", "OPTIONS /api/v1/notes/blocks", "POST /api/v1/notes/blocks",
            "PUT /api/v1/notes/blocks", "DELETE /api/v1/notes/blocks/extra/extra",
        ],
    )
    fun given__unexposed_neighbor_path_or_method__when__requesting_with_a_valid_token__then__deny_before_all_dependencies(
        request: String,
    ) {
        val parts = request.split(" ", limit = 2)

        mvc.perform(
            request(HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())

        verifyNoInteractions(members, batches, blocks, recipientBlocks, statusBlocks, counts)
    }

    @Test
    fun given__existing_recipient_status_and_admin_routes__when__their_own_rejections_occur__then__retain_scoped_policies_without_block_list_queries() {
        whenever(members.get(7)).thenReturn(active(7))

        mvc.perform(
            get("/api/v1/notes/recipients/7").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json("{\"message\":\"이 상대에게 쪽지를 보낼 수 없습니다.\"}", JsonCompareMode.STRICT))
        mvc.perform(
            get("/api/v1/notes/blocks/status")
                .param("memberIds", "0")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json("{\"message\":\"작성자 식별자는 1~100개의 양수여야 합니다.\"}", JsonCompareMode.STRICT))
        mvc.perform(
            get("/api/v1/admin/users/stats/total-users")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )

        verify(members).get(7)
        verifyNoMoreInteractions(members)
        verifyNoInteractions(batches, blocks, recipientBlocks, statusBlocks, counts)
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
