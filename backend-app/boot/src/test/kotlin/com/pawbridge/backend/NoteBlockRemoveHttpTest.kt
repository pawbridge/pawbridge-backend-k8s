package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.note.application.NoteBlockRemoval
import com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader
import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors
import com.pawbridge.backend.community.internal.note.application.RemoveNoteBlock
import com.pawbridge.backend.community.internal.note.web.NoteBlockRemoveController
import com.pawbridge.backend.community.internal.note.web.NoteBlockRemoveExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusController
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusExceptionHandler
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
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

    @MockkBean
    lateinit var members: ContactMemberQuery

    @MockkBean
    lateinit var removal: NoteBlockRemoval

    @MockkBean
    lateinit var statuses: NoteBlockStatusReader

    @MockkBean
    lateinit var counts: TotalUserCountQuery

    @ParameterizedTest
    @ValueSource(longs = [7, Long.MAX_VALUE])
    fun `서명된 호출자와 위조 신원이 주어졌을 때__차단을 해제하면__현재 회원 확인 뒤 빈 200을 준다`(
        target: Long,
    ) {
        // given
        every { members.get(any()) } returns active()

        // The mock models the port's callback position, not database locks, row counts or a transaction.
        every { removal.remove(any(), any(), any()) } answers {
            thirdArg<Runnable>().run()
        }

        // when
        val result = mvc.perform(
            authenticated(PATH + target)
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":999}"),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(content().string(""))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verifySequence {
            removal.remove(7L, target, any())
            members.get(7)
        }
        confirmVerified(removal, members, statuses, counts)
        contextCleared()
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `양수가 아닌 대상과 유효한 토큰이 주어졌을 때__해제하면__해제와 회원 조회 전에 로그인 401을 준다`(
        target: Long,
    ) {
        // given
        val request = authenticated(PATH + target)

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"로그인이 필요합니다.\"}", JsonCompareMode.STRICT))
        noDependencies()
    }

    @ParameterizedTest
    @ValueSource(strings = ["not-a-long", "9223372036854775808", "status"])
    fun `깨지거나 넘치는 대상과 유효한 토큰이 주어졌을 때__해제하면__업무 처리 전에 메시지만 담은 400을 준다`(
        target: String,
    ) {
        // given
        val request = authenticated(PATH + target)

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT))
        noDependencies()
    }

    @Test
    fun `탈퇴 대기 호출자가 주어졌을 때__해제가 가드를 부르면__메시지만 담은 403을 준다`() {
        // given
        every { members.get(any()) } returns ContactMember(7L, "탈퇴한 회원", false, true)
        every { removal.remove(any(), any(), any()) } answers {
            thirdArg<Runnable>().run()
        }

        // when
        val result = mvc.perform(authenticated(PATH + "8"))

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT))
        verify(exactly = 1) { removal.remove(7L, 8L, any()) }
        verify(exactly = 1) { members.get(7) }
        confirmVerified(removal, members, statuses, counts)
        contextCleared()
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    fun `회원 저장소나 쓰기 런타임 오류가 났을 때__해제하면__원인별 503을 주고 컨텍스트를 비운다`(
        phase: String,
        failure: RuntimeException,
        message: String,
    ) {
        // given
        val beforeGuard = listOf("BEGIN", "TIMEOUT", "USAGE").contains(phase)
        if (phase == "USER") {
            every { members.get(any()) } throws failure
        } else if (!beforeGuard) {
            every { members.get(any()) } returns active()
        }

        every { removal.remove(any(), any(), any()) } answers {
            if (beforeGuard) {
                throw failure
            }
            thirdArg<Runnable>().run()
            throw failure
        }

        // when
        val result = mvc.perform(authenticated(PATH + "8"))

        // then
        result
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to message)), JsonCompareMode.STRICT))
        verify(exactly = 1) { removal.remove(7L, 8L, any()) }
        if (!beforeGuard) {
            verify(exactly = 1) { members.get(7) }
        }
        confirmVerified(removal, members, statuses, counts)
        contextCleared()
    }

    @Test
    fun `읽기 전용 트랜잭션 오류가 났을 때__차단 상태를 조회하면__해제 어드바이스 밖에서 저장소 503을 유지한다`() {
        // given
        every { members.get(any()) } returns active()
        every { statuses.blockedAuthors(any(), any()) } throws TransactionSystemException("synthetic-status-commit")

        // when
        val result = mvc.perform(
            get("/api/v1/notes/blocks/status")
                .param("memberIds", "8")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to STORAGE)), JsonCompareMode.STRICT))
        verify(exactly = 1) { members.get(7) }
        verify(exactly = 1) { statuses.blockedAuthors(7, listOf(8L)) }
        confirmVerified(members, statuses, removal, counts)
        contextCleared()
    }

    @Test
    fun `관리자 권한과 런타임 오류가 주어졌을 때__사용자 수를 세면__해제 어드바이스 밖에서 403과 500을 유지한다`() {
        // given
        every { counts.count() } throws IllegalStateException("synthetic-admin-count")

        // when
        val forbidden = mvc.perform(
            get("/api/v1/admin/users/stats/total-users")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
        confirmVerified(counts)
        val failed = mvc.perform(
            get("/api/v1/admin/users/stats/total-users")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        )

        // then
        forbidden
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        failed
            .andExpect(status().isInternalServerError())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        verify(exactly = 1) { counts.count() }
        confirmVerified(counts, removal, members, statuses)
        contextCleared()
    }

    private fun authenticated(path: String): MockHttpServletRequestBuilder {
        return delete(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
    }

    private fun noDependencies() {
        confirmVerified(removal, members, statuses, counts)
        contextCleared()
    }

    companion object {
        private const val PATH = "/api/v1/notes/blocks/"
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
