package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.note.application.CreateNoteBlock
import com.pawbridge.backend.community.internal.note.application.NoteBlockCreation
import com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader
import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors
import com.pawbridge.backend.community.internal.note.web.NoteBlockCreateController
import com.pawbridge.backend.community.internal.note.web.NoteBlockCreateExceptionHandler
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

    @MockkBean
    lateinit var members: ContactMemberQuery

    @MockkBean
    lateinit var creation: NoteBlockCreation

    @MockkBean
    lateinit var clock: Clock

    @MockkBean
    lateinit var statuses: NoteBlockStatusReader

    @MockkBean
    lateinit var counts: TotalUserCountQuery

    @ParameterizedTest
    @ValueSource(longs = [8, Long.MAX_VALUE])
    fun `검증된 호출자와 위조 헤더가 주어졌을 때__차단하면__회원 조회 후 시계를 한 번 읽고 빈 200을 준다`(
        target: Long,
    ) {
        // given
        every { members.get(7) } returns active(7)
        every { members.get(target) } returns active(target)
        every { clock.instant() } returns NOW
        every { creation.create(any(), any(), any()) } answers {
            val afterActive = thirdArg<Supplier<Instant>>()
            assertThat(afterActive.get()).isEqualTo(NOW)
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
            creation.create(7L, target, any())
            members.get(7)
            members.get(target)
            clock.instant()
        }
        confirmVerified(creation, members, clock, statuses, counts)
        contextCleared()
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `양수가 아닌 대상과 유효한 토큰이 주어졌을 때__차단하면__의존성 호출 전에 로그인 401을 준다`(
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

    @Test
    fun `자기 자신이 대상일 때__차단하면__포트와 회원 조회 없이 400으로 거부한다`() {
        // given
        val request = authenticated(PATH + "7")

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"자신을 차단할 수 없습니다.\"}", JsonCompareMode.STRICT))
        noDependencies()
    }

    @ParameterizedTest
    @ValueSource(strings = ["CURRENT", "TARGET"])
    fun `탈퇴 대기 회원이 호출자나 대상일 때__차단하면__시계 전에 403을 주고 조회 순서를 지킨다`(
        phase: String,
    ) {
        // given
        every { members.get(7) } returns if (phase == "CURRENT") pending(7) else active(7)
        if (phase == "TARGET") {
            every { members.get(8) } returns pending(8)
        }
        every { creation.create(any(), any(), any()) } answers {
            val afterActive = thirdArg<Supplier<Instant>>()
            afterActive.get()
        }

        // when
        val result = mvc.perform(authenticated(PATH + "8"))

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT))
        verifySequence {
            creation.create(7L, 8L, any())
            members.get(7)
            if (phase == "TARGET") {
                members.get(8)
            }
        }
        confirmVerified(creation, members, clock, statuses, counts)
        contextCleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["CURRENT", "TARGET"])
    fun `회원 조회가 타입 있는 오류로 실패했을 때__차단하면__회원 503을 주고 시계를 건너뛴다`(
        phase: String,
    ) {
        // given
        val failure = ContactMemberUnavailableException(TransactionSystemException("synthetic-private-user-commit"))
        if (phase == "CURRENT") {
            every { members.get(7) } throws failure
        } else {
            every { members.get(7) } returns active(7)
            every { members.get(8) } throws failure
        }
        every { creation.create(any(), any(), any()) } answers {
            val afterActive = thirdArg<Supplier<Instant>>()
            afterActive.get()
        }

        // when
        val result = mvc.perform(authenticated(PATH + "8"))

        // then
        result
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to MEMBER)), JsonCompareMode.STRICT))
        verifySequence {
            creation.create(7L, 8L, any())
            members.get(7)
            if (phase == "TARGET") {
                members.get(8)
            }
        }
        confirmVerified(creation, members, clock, statuses, counts)
        contextCleared()
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    fun `저장소나 트랜잭션 오류가 났을 때__차단하면__실제 오류 유형대로 503을 주고 컨텍스트를 비운다`(
        phase: String,
        failure: RuntimeException,
        message: String,
    ) {
        // given
        val beforeSupplier = listOf("BEGIN", "TIMEOUT", "USAGE").contains(phase)
        if (!beforeSupplier) {
            every { members.get(7) } returns active(7)
            every { members.get(8) } returns active(8)
            every { clock.instant() } returns NOW
        }

        // Types and callback order only: these mocks do not emulate begin/commit, SQL or rollback.
        every { creation.create(any(), any(), any()) } answers {
            if (beforeSupplier) {
                throw failure
            }
            val afterActive = thirdArg<Supplier<Instant>>()
            afterActive.get()
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
        verify(exactly = 1) { creation.create(7L, 8L, any()) }
        if (!beforeSupplier) {
            verifySequence {
                members.get(7)
                members.get(8)
                clock.instant()
            }
        }
        confirmVerified(creation, members, clock, statuses, counts)
        contextCleared()
    }

    private fun authenticated(path: String): MockHttpServletRequestBuilder {
        return put(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
    }

    private fun noDependencies() {
        confirmVerified(creation, members, clock, statuses, counts)
        contextCleared()
    }

    companion object {
        private const val PATH = "/api/v1/notes/blocks/"
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
