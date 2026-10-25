package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.note.application.NoteBlockRemoval
import com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader
import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors
import com.pawbridge.backend.community.internal.note.application.RemoveNoteBlock
import com.pawbridge.backend.community.internal.note.web.NoteBlockRemoveController
import com.pawbridge.backend.community.internal.note.web.NoteBlockRemoveExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusController
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusExceptionHandler
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.stubbing.Answer
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.TransactionTimedOutException
import java.util.stream.Stream

@WebMvcTest(
    controllers = [NoteBlockRemoveController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        NoteBlockRemoveController::class,
        NoteBlockRemoveExceptionHandler::class,
        RemoveNoteBlock::class,
        NoteBlockStatusController::class,
        NoteBlockStatusExceptionHandler::class,
        ReadBlockedAuthors::class,
        TotalUserCountController::class,
        TotalUserCountExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class NoteBlockRemoveHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockitoBean
    lateinit var members: ContactMemberQuery

    @MockitoBean
    lateinit var removal: NoteBlockRemoval

    @MockitoBean
    lateinit var statuses: NoteBlockStatusReader

    @MockitoBean
    lateinit var counts: TotalUserCountQuery

    @ParameterizedTest
    @ValueSource(longs = [7, Long.MAX_VALUE])
    fun given__self_or_full_long_target_and_spoofed_identity__when__removing__then__use_signed_caller_and_return_empty_200_after_current_callback(
        target: Long,
    ) {
        whenever(members.get(7)).thenReturn(active())
        // The mock models the port's callback position, not database locks, row counts or a transaction.
        doAnswer(
            Answer<Any?> { call ->
                call.getArgument<Runnable>(2).run()
                null
            },
        ).`when`(removal).remove(eq(7L), eq(target), anyNonNull<Runnable>())

        mvc.perform(
            authenticated(PATH + target)
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":999}"),
        )
            .andExpect(status().isOk())
            .andExpect(content().string(""))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))

        val order = inOrder(removal, members)
        order.verify(removal).remove(eq(7L), eq(target), anyNonNull<Runnable>())
        order.verify(members).get(7)
        verifyNoMoreInteractions(removal, members)
        verifyNoInteractions(statuses, counts)
        contextCleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING", "INVALID"])
    fun given__missing_or_bad_jwt_and_malformed_target__when__removing__then__authenticate_before_binding_or_business_work(
        token: String,
    ) {
        val request = delete(PATH + "not-a-long").header("X-User-Id", "7")
        if (token == "INVALID") {
            request.header("Authorization", "Bearer invalid")
        }
        val message = if (token == "MISSING") "인증 토큰이 필요합니다." else "유효하지 않거나 만료된 토큰입니다."
        mvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT),
            )
        noDependencies()
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun given__nonpositive_target_with_valid_jwt__when__removing__then__keep_business_login_401_before_removal_or_member_lookup(
        target: Long,
    ) {
        mvc.perform(authenticated(PATH + target))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"로그인이 필요합니다.\"}", JsonCompareMode.STRICT))
        noDependencies()
    }

    @ParameterizedTest
    @ValueSource(strings = ["not-a-long", "9223372036854775808", "status"])
    fun given__malformed_or_overflow_target_with_valid_jwt__when__removing__then__return_message_only_400_before_business_work(
        target: String,
    ) {
        mvc.perform(authenticated(PATH + target))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT))
        noDependencies()
    }

    @Test
    fun given__pending_current_member__when__removal_invokes_its_guard__then__return_message_only_403() {
        whenever(members.get(7)).thenReturn(ContactMember(7L, "탈퇴한 회원", false, true))
        doAnswer(
            Answer<Any?> { call ->
                call.getArgument<Runnable>(2).run()
                null
            },
        ).`when`(removal).remove(eq(7L), eq(8L), anyNonNull<Runnable>())

        mvc.perform(authenticated(PATH + "8"))
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT))
        verify(removal).remove(eq(7L), eq(8L), anyNonNull<Runnable>())
        verify(members).get(7)
        verifyNoMoreInteractions(removal, members)
        verifyNoInteractions(statuses, counts)
        contextCleared()
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    fun given__member_dao_or_writer_runtime_failure__when__removing__then__keep_source_specific_503_and_clear_security_context(
        phase: String,
        failure: RuntimeException,
        message: String,
    ) {
        val beforeGuard = listOf("BEGIN", "TIMEOUT", "USAGE").contains(phase)
        if (phase == "USER") {
            whenever(members.get(7)).thenThrow(failure)
        } else if (!beforeGuard) {
            whenever(members.get(7)).thenReturn(active())
        }
        doAnswer(
            Answer<Any?> { call ->
                if (beforeGuard) {
                    throw failure
                }
                call.getArgument<Runnable>(2).run()
                throw failure
            },
        ).`when`(removal).remove(eq(7L), eq(8L), anyNonNull<Runnable>())

        mvc.perform(authenticated(PATH + "8"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to message)), JsonCompareMode.STRICT))
        verify(removal).remove(eq(7L), eq(8L), anyNonNull<Runnable>())
        if (beforeGuard) {
            verifyNoInteractions(members)
        } else {
            verify(members).get(7)
        }
        verifyNoMoreInteractions(removal, members)
        verifyNoInteractions(statuses, counts)
        contextCleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING", "INVALID", "VALID"])
    fun given__unexposed_stream_extension_with_missing_bad_or_valid_jwt__when__requesting__then__retain_default_403_without_removal_or_sse_work(
        token: String,
    ) {
        val request = get("/api/v1/notes/stream/extra").accept(MediaType.TEXT_EVENT_STREAM)
        if (token != "MISSING") {
            request.header(
                "Authorization",
                if (token == "INVALID") "Bearer invalid" else "Bearer " + F3TestInputs.token(7, "ROLE_USER"),
            )
        }
        mvc.perform(request)
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        noDependencies()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "PUT /api/v1/notes/00000000-0000-0000-0000-000000000008/read/extra",
            "DELETE /api/v1/notes/00000000-0000-0000-0000-000000000008/extra",
            "DELETE /api/v1/notes/blocks/8/extra",
        ],
    )
    fun given__unexposed_block_note_mutation_or_deeper_path__when__requesting__then__retain_default_403_without_removal_work(
        wire: String,
    ) {
        val parts = wire.split(" ", limit = 2)
        mvc.perform(
            request(HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        noDependencies()
    }

    @Test
    fun given__existing_read_only_tx_failure__when__querying_block_status__then__keep_its_storage_policy_outside_remove_advice() {
        whenever(members.get(7)).thenReturn(active())
        whenever(statuses.blockedAuthors(7, listOf(8L))).thenThrow(TransactionSystemException("synthetic-status-commit"))
        mvc.perform(
            get("/api/v1/notes/blocks/status")
                .param("memberIds", "8")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to STORAGE)), JsonCompareMode.STRICT))
        verify(members).get(7)
        verify(statuses).blockedAuthors(7, listOf(8L))
        verifyNoMoreInteractions(members, statuses)
        verifyNoInteractions(removal, counts)
        contextCleared()
    }

    @Test
    fun given__existing_admin_permission_and_runtime_error__when__counting_users__then__keep_admin_403_and_common_500_outside_remove_advice() {
        whenever(counts.count()).thenThrow(IllegalStateException("synthetic-admin-count"))
        mvc.perform(
            get("/api/v1/admin/users/stats/total-users")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(counts)
        mvc.perform(
            get("/api/v1/admin/users/stats/total-users")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        )
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        verify(counts).count()
        verifyNoMoreInteractions(counts)
        verifyNoInteractions(removal, members, statuses)
        contextCleared()
    }

    private fun authenticated(path: String): MockHttpServletRequestBuilder {
        return delete(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
    }

    private fun noDependencies() {
        verifyNoInteractions(removal, members, statuses, counts)
        contextCleared()
    }

    companion object {
        private const val PATH = "/api/v1/notes/blocks/"
        private const val SECURITY_CACHE = "no-cache, no-store, max-age=0, must-revalidate"
        private const val MEMBER = "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
        private const val STORAGE = "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."
        private const val GENERIC = "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."

        @JvmStatic
        fun dependencyFailures(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    "USER",
                    ContactMemberUnavailableException(TransactionSystemException("synthetic-private-user-commit")),
                    MEMBER,
                ),
                Arguments.of("DAO", DataAccessResourceFailureException("synthetic-private-delete"), STORAGE),
                Arguments.of("BEGIN", CannotCreateTransactionException("synthetic-private-begin"), GENERIC),
                Arguments.of("COMMIT", TransactionSystemException("synthetic-private-commit"), GENERIC),
                Arguments.of("TIMEOUT", TransactionTimedOutException("synthetic-private-timeout"), GENERIC),
                Arguments.of("USAGE", IllegalTransactionStateException("synthetic-private-usage"), GENERIC),
                Arguments.of("RUNTIME", IllegalStateException("synthetic-private-runtime"), GENERIC),
            )
        }

        private fun contextCleared() {
            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }

        private fun active(): ContactMember = ContactMember(7L, "회원", true, false)
    }
}
