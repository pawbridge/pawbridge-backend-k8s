package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.note.application.CreateNoteBlock
import com.pawbridge.backend.community.internal.note.application.NoteBlockCreation
import com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader
import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors
import com.pawbridge.backend.community.internal.note.web.NoteBlockCreateController
import com.pawbridge.backend.community.internal.note.web.NoteBlockCreateExceptionHandler
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
import org.mockito.ArgumentMatchers.anyLong
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.TransactionTimedOutException
import java.time.Clock
import java.time.Instant
import java.util.function.Supplier
import java.util.stream.Stream

@WebMvcTest(
    controllers = [NoteBlockCreateController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        NoteBlockCreateController::class,
        NoteBlockCreateExceptionHandler::class,
        CreateNoteBlock::class,
        NoteBlockStatusController::class,
        NoteBlockStatusExceptionHandler::class,
        ReadBlockedAuthors::class,
        TotalUserCountController::class,
        TotalUserCountExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class NoteBlockCreateHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockitoBean
    lateinit var members: ContactMemberQuery

    @MockitoBean
    lateinit var creation: NoteBlockCreation

    @MockitoBean
    lateinit var clock: Clock

    @MockitoBean
    lateinit var statuses: NoteBlockStatusReader

    @MockitoBean
    lateinit var counts: TotalUserCountQuery

    @ParameterizedTest
    @ValueSource(longs = [8, Long.MAX_VALUE])
    fun given__verified_caller_full_long_target_and_forged_headers__when__creating__then__return_empty_200_after_current_target_and_clock_once(
        target: Long,
    ) {
        whenever(members.get(7)).thenReturn(active(7))
        whenever(members.get(target)).thenReturn(active(target))
        whenever(clock.instant()).thenReturn(NOW)
        invokeAfterMemberLocks()

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

        val order = inOrder(creation, members, clock)
        order.verify(creation).create(eq(7L), eq(target), anyNonNull<Supplier<Instant>>())
        order.verify(members).get(7)
        order.verify(members).get(target)
        order.verify(clock).instant()
        verifyNoMoreInteractions(creation, members, clock)
        verifyNoInteractions(statuses, counts)
        contextCleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING", "INVALID"])
    fun given__missing_or_bad_jwt_and_malformed_target__when__creating__then__authenticate_before_binding_or_dependencies(
        token: String,
    ) {
        val request = put(PATH + "not-a-long").header("X-User-Id", "7")
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
    fun given__nonpositive_target_with_valid_jwt__when__creating__then__return_business_login_401_before_port_members_or_clock(
        target: Long,
    ) {
        mvc.perform(authenticated(PATH + target))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"로그인이 필요합니다.\"}", JsonCompareMode.STRICT))
        noDependencies()
    }

    @Test
    fun given__positive_self_target__when__creating__then__reject_400_before_port_or_current_member_lookup() {
        mvc.perform(authenticated(PATH + "7"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"자신을 차단할 수 없습니다.\"}", JsonCompareMode.STRICT))
        noDependencies()
    }

    @ParameterizedTest
    @ValueSource(strings = ["not-a-long", "9223372036854775808", "status"])
    fun given__malformed_overflow_or_former_status_neighbor__when__authenticated_put_binds__then__return_message_only_400_before_business_work(
        target: String,
    ) {
        mvc.perform(authenticated(PATH + target))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT))
        noDependencies()
    }

    @ParameterizedTest
    @ValueSource(strings = ["CURRENT", "TARGET"])
    fun given__pending_current_or_target__when__creation_invokes_its_supplier__then__return_403_before_clock_with_original_lookup_order(
        phase: String,
    ) {
        whenever(members.get(7)).thenReturn(if (phase == "CURRENT") pending(7) else active(7))
        if (phase == "TARGET") {
            whenever(members.get(8)).thenReturn(pending(8))
        }
        invokeAfterMemberLocks()

        mvc.perform(authenticated(PATH + "8"))
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT))
        val order = inOrder(creation, members)
        order.verify(creation).create(eq(7L), eq(8L), anyNonNull<Supplier<Instant>>())
        order.verify(members).get(7)
        if (phase == "TARGET") {
            order.verify(members).get(8)
        }
        verifyNoMoreInteractions(creation, members)
        verifyNoInteractions(clock, statuses, counts)
        contextCleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["CURRENT", "TARGET"])
    fun given__typed_user_failure_at_current_or_late_target__when__creating__then__preserve_member_503_and_skip_clock(
        phase: String,
    ) {
        val failure = ContactMemberUnavailableException(TransactionSystemException("synthetic-private-user-commit"))
        if (phase == "CURRENT") {
            whenever(members.get(7)).thenThrow(failure)
        } else {
            whenever(members.get(7)).thenReturn(active(7))
            whenever(members.get(8)).thenThrow(failure)
        }
        invokeAfterMemberLocks()

        mvc.perform(authenticated(PATH + "8"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to MEMBER)), JsonCompareMode.STRICT))
        val order = inOrder(creation, members)
        order.verify(creation).create(eq(7L), eq(8L), anyNonNull<Supplier<Instant>>())
        order.verify(members).get(7)
        if (phase == "TARGET") {
            order.verify(members).get(8)
        }
        verifyNoMoreInteractions(creation, members)
        verifyNoInteractions(clock, statuses, counts)
        contextCleared()
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    fun given__dao_concrete_tx_usage_or_runtime__when__creating__then__map_actual_failure_type_and_clear_security_context(
        phase: String,
        failure: RuntimeException,
        message: String,
    ) {
        val beforeSupplier = listOf("BEGIN", "TIMEOUT", "USAGE").contains(phase)
        if (!beforeSupplier) {
            whenever(members.get(7)).thenReturn(active(7))
            whenever(members.get(8)).thenReturn(active(8))
            whenever(clock.instant()).thenReturn(NOW)
        }
        // Types and callback order only: these mocks do not emulate begin/commit, SQL or rollback.
        doAnswer(
            Answer<Any?> { call ->
                if (beforeSupplier) {
                    throw failure
                }
                val afterActive = call.getArgument<Supplier<Instant>>(2)
                afterActive.get()
                throw failure
            },
        ).`when`(creation).create(eq(7L), eq(8L), anyNonNull<Supplier<Instant>>())

        mvc.perform(authenticated(PATH + "8"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to message)), JsonCompareMode.STRICT))
        verify(creation).create(eq(7L), eq(8L), anyNonNull<Supplier<Instant>>())
        if (beforeSupplier) {
            verifyNoInteractions(members, clock)
        } else {
            val order = inOrder(members, clock)
            order.verify(members).get(7)
            order.verify(members).get(8)
            order.verify(clock).instant()
        }
        verifyNoMoreInteractions(creation, members, clock)
        verifyNoInteractions(statuses, counts)
        contextCleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["PUT /api/v1/notes/blocks/8/extra", "PUT /api/v1/notes/blocks/8/"])
    fun given__deeper_or_trailing_put_path__when__requesting_with_valid_jwt__then__keep_default_403_before_creation(
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
    fun given__existing_read_only_status_tx_failure__when__reading__then__keep_storage_503_outside_creation_advice() {
        whenever(members.get(7)).thenReturn(active(7))
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
        verifyNoInteractions(creation, clock, counts)
        contextCleared()
    }

    @Test
    fun given__existing_admin_permission_and_runtime_error__when__counting_users__then__keep_admin_403_and_common_500_outside_creation_advice() {
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
        verifyNoInteractions(creation, members, clock, statuses)
        contextCleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_ADMIN", "", "ROLE_UNRECOGNIZED"])
    fun given__verified_existing_non_user_role_policy__when__creating__then__keep_authenticated_access_without_new_role_restrictions(
        role: String,
    ) {
        whenever(members.get(7)).thenReturn(active(7))
        whenever(members.get(8)).thenReturn(active(8))
        whenever(clock.instant()).thenReturn(NOW)
        invokeAfterMemberLocks()
        mvc.perform(put(PATH + "8").header("Authorization", "Bearer " + F3TestInputs.token(7, role)))
            .andExpect(status().isOk())
            .andExpect(content().string(""))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verify(creation).create(eq(7L), eq(8L), anyNonNull<Supplier<Instant>>())
        verify(members).get(7)
        verify(members).get(8)
        verify(clock).instant()
        verifyNoMoreInteractions(creation, members, clock)
        verifyNoInteractions(statuses, counts)
        contextCleared()
    }

    private fun invokeAfterMemberLocks() {
        // A port contract stub, not evidence that database locks or a writer transaction exist.
        doAnswer(
            Answer<Any?> { call ->
                val afterActive = call.getArgument<Supplier<Instant>>(2)
                assertThat(afterActive.get()).isEqualTo(NOW)
                null
            },
        ).`when`(creation).create(anyLong(), anyLong(), anyNonNull<Supplier<Instant>>())
    }

    private fun authenticated(path: String): MockHttpServletRequestBuilder {
        return put(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
    }

    private fun noDependencies() {
        verifyNoInteractions(creation, members, clock, statuses, counts)
        contextCleared()
    }

    companion object {
        private const val PATH = "/api/v1/notes/blocks/"
        private const val SECURITY_CACHE = "no-cache, no-store, max-age=0, must-revalidate"
        private const val MEMBER = "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
        private const val STORAGE = "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."
        private const val GENERIC = "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."
        private val NOW: Instant = Instant.parse("2031-01-01T00:00:00.123456Z")

        @JvmStatic
        fun dependencyFailures(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("DAO", DataAccessResourceFailureException("synthetic-private-insert"), STORAGE),
                Arguments.of("BEGIN", CannotCreateTransactionException("synthetic-private-begin"), GENERIC),
                Arguments.of("COMMIT", TransactionSystemException("synthetic-private-commit"), GENERIC),
                Arguments.of("TIMEOUT", TransactionTimedOutException("synthetic-private-timeout"), GENERIC),
                Arguments.of("USAGE", IllegalTransactionStateException("synthetic-private-usage"), GENERIC),
                Arguments.of("RUNTIME", IllegalStateException("synthetic-private-runtime"), GENERIC),
            )
        }

        private fun active(id: Long): ContactMember = ContactMember(id, "합성 회원", true, false)

        private fun pending(id: Long): ContactMember = ContactMember(id, "탈퇴한 회원", false, true)

        private fun contextCleared() {
            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }
    }
}
