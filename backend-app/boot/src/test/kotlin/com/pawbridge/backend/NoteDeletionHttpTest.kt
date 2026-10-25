package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.note.application.DeletePrivateNote
import com.pawbridge.backend.community.internal.note.application.NoteDeletion
import com.pawbridge.backend.community.internal.note.web.NoteDeletionController
import com.pawbridge.backend.community.internal.note.web.NoteDeletionExceptionHandler
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler
import io.jsonwebtoken.Jwts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.stubbing.Answer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.TransactionSystemException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier
import java.util.stream.Stream

/** Real MVC/JWT and current-member callback; the mocked port proves no SQL, commit, FK or SSE behavior. */
@WebMvcTest(
    controllers = [NoteDeletionController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        NoteDeletionController::class,
        NoteDeletionExceptionHandler::class,
        DeletePrivateNote::class,
        TotalUserCountController::class,
        TotalUserCountExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class NoteDeletionHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var members: ContactMemberQuery

    @MockitoBean
    lateinit var mutation: NoteDeletion

    @MockitoBean(name = "readClock")
    lateinit var clock: Clock

    @MockitoBean
    lateinit var counts: TotalUserCountQuery

    @ParameterizedTest
    @ValueSource(strings = ["USER", "EXPIRYLESS_ADMIN"])
    fun givenSignedUserOrExpirylessAdminRestToken__whenDeletingWithSpoofedHeaders__thenUseOnlyVerifiedCallerAndReturnEmptyNoStore200(
        tokenKind: String,
    ) {
        whenever(members.get(7)).thenReturn(active())
        doAnswer(
            Answer<Any?> { call ->
                call.getArgument(2, Runnable::class.java).run()
                null
            },
        ).`when`(mutation).delete(eq(7L), eq(NOTE), anyNonNull<Runnable>(), anyNonNull<Supplier<Instant>>())
        // Expiry remains optional for general REST; exact stream keeps its separate expiry requirement.
        val token = if (tokenKind == "USER") {
            F3TestInputs.token(7, "ROLE_USER")
        } else {
            Jwts.builder().claim("userId", 7L).claim("role", "ROLE_ADMIN").signWith(F3TestInputs.KEY).compact()
        }

        mvc.perform(
            delete(PATH)
                .header("Authorization", "Bearer $token")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .header("X-Auth-Expires-At", Long.MAX_VALUE),
        )
            .andExpect(status().isOk())
            .andExpect(content().string(""))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))

        verify(mutation).delete(eq(7L), eq(NOTE), anyNonNull<Runnable>(), anyNonNull<Supplier<Instant>>())
        verify(members).get(7)
        verifyNoMoreInteractions(mutation, members)
        verifyNoInteractions(clock, counts)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING", "INVALID"])
    fun givenMissingOrBadTokenAndMalformedUuid__whenDeleting__thenAuthenticateBeforeBindingWithoutDependencies(
        kind: String,
    ) {
        val request = delete("/api/v1/notes/not-a-uuid").header("X-User-Id", "7")
        if (kind == "INVALID") {
            request.header("Authorization", "Bearer invalid")
        }
        val message = if (kind == "MISSING") "인증 토큰이 필요합니다." else "유효하지 않거나 만료된 토큰입니다."
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
    @ValueSource(strings = ["not-a-uuid", "blocks", "notifications"])
    fun givenVerifiedCallerAndSingleNonUuidSegmentIncludingStaticReadLabels__whenDeleting__thenReturnScoped400WithoutReservedLabelExceptions(
        segment: String,
    ) {
        mvc.perform(
            delete("/api/v1/notes/$segment").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT))
        noDependencies()
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun givenCurrentOrDeletionPortFailure__whenDeleting__thenKeepInactiveNotFoundMemberStorageAndWriterRuntimePolicies(
        kind: String,
        status: Int,
        message: String,
    ) {
        val failure: RuntimeException? = when (kind) {
            "inactive" -> null
            "missing" -> NoteDeletion.MissingOwnedNote()
            "member" -> ContactMemberUnavailableException(IllegalStateException("private-member"))
            "DAO" -> DataAccessResourceFailureException("private-SQL-body")
            else -> TransactionSystemException("private-commit-detail")
        }
        if (kind == "member") {
            whenever(members.get(7)).thenThrow(failure!!)
        } else {
            whenever(members.get(7))
                .thenReturn(if (kind == "inactive") ContactMember(7L, "", false, true) else active())
        }
        doAnswer(
            Answer<Any?> { call ->
                call.getArgument(2, Runnable::class.java).run()
                if (failure != null) {
                    throw failure
                }
                null
            },
        ).`when`(mutation).delete(eq(7L), eq(NOTE), anyNonNull<Runnable>(), anyNonNull<Supplier<Instant>>())

        mvc.perform(delete(PATH).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
            .andExpect(status().`is`(status))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"" + message + "\"}", JsonCompareMode.STRICT))

        verify(mutation).delete(eq(7L), eq(NOTE), anyNonNull<Runnable>(), anyNonNull<Supplier<Instant>>())
        verify(members).get(7)
        verifyNoMoreInteractions(mutation, members)
        verifyNoInteractions(clock, counts)
        cleared()
    }

    @Test
    fun givenAdminCountOutsideDeletionAdvice__whenUserOrAdminRequestsWithSpoofedRole__thenKeepRole403AndCommon500() {
        whenever(counts.count()).thenThrow(IllegalStateException("private-count"))
        mvc.perform(
            get(ADMIN_COUNT)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .header("X-User-Role", "ROLE_ADMIN"),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        mvc.perform(get(ADMIN_COUNT).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        verify(counts).count()
        verifyNoMoreInteractions(counts)
        verifyNoInteractions(members, mutation, clock)
        cleared()
    }

    private fun noDependencies() {
        verifyNoInteractions(members, mutation, clock, counts)
        cleared()
    }

    companion object {
        private val NOTE: UUID = UUID.fromString("f2800000-0000-0000-0000-000000000001")
        private val PATH = "/api/v1/notes/" + NOTE
        private const val ADMIN_COUNT = "/api/v1/admin/users/stats/total-users"
        private const val SECURITY_CACHE = "no-cache, no-store, max-age=0, must-revalidate"

        @JvmStatic
        fun failures(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("inactive", 403, "이용할 수 없는 회원입니다."),
                Arguments.of("missing", 404, "쪽지를 찾을 수 없습니다."),
                Arguments.of("member", 503, "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."),
                Arguments.of("DAO", 503, "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
                Arguments.of("TX", 503, "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),
            )
        }

        private fun active(): ContactMember = ContactMember(7L, " 原文 ", true, false)

        private fun cleared() {
            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }
    }
}
