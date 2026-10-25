package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.note.application.MarkPrivateNoteRead
import com.pawbridge.backend.community.internal.note.application.NoteReadMutation
import com.pawbridge.backend.community.internal.note.application.RequireActiveNoteStream
import com.pawbridge.backend.community.internal.note.web.NoteReadMutationController
import com.pawbridge.backend.community.internal.note.web.NoteReadMutationExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteStreamController
import com.pawbridge.backend.community.internal.note.web.NoteStreamExceptionHandler
import com.pawbridge.backend.community.internal.note.web.PrivateNoteStreamRegistry
import com.pawbridge.backend.support.TestFaker
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import io.jsonwebtoken.Jwts
import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.TransactionSystemException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID
import java.util.function.Supplier
import java.util.stream.Stream

/** Real MVC/security/async dispatch with a real Spring emitter; mutation and registry are mocked, not PG/socket evidence. */
@WebMvcTest(
    controllers = [NoteStreamController::class, NoteReadMutationController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        NoteStreamController::class,
        NoteStreamExceptionHandler::class,
        RequireActiveNoteStream::class,
        NoteReadMutationController::class,
        NoteReadMutationExceptionHandler::class,
        MarkPrivateNoteRead::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class NoteReadStreamHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var members: ContactMemberQuery

    @MockkBean
    lateinit var mutation: NoteReadMutation

    // Relaxed only because Spring calls SmartLifecycle methods (isAutoStartup, getPhase) while building the context,
    // before any test body can stub them. Test behavior is still checked with explicit stubs and confirmVerified.
    @MockkBean(relaxed = true)
    lateinit var streams: PrivateNoteStreamRegistry

    @MockkBean
    lateinit var clock: Clock

    @BeforeEach
    fun clearOnlyContainerLifecycleProbes() {
        // Spring may inspect this SmartLifecycle mock while creating the test context.
        clearMocks(streams, answers = false)
    }

    @Test
    fun `검증된 만료와 위조 헤더가 주어졌을 때__스트림을 열고 완료하면__호출자 만료와 ready를 유지하고 세션이 없다`() {
        // given
        val expiry = Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.SECONDS)
        val client = TestFaker.uuid()
        val emitter = SseEmitter(120_000L)
        emitter.send(SseEmitter.event().name("ready").data("{}"))

        every { members.get(any()) } returns active()
        every { streams.open(any(), any(), any()) } returns emitter

        // when
        val result = mvc.perform(
            get(STREAM)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .param("clientId", client.toString())
                .header("Authorization", "Bearer " + token(expiry))
                .header("X-User-Id", "999")
                .header("X-Auth-Expires-At", Long.MAX_VALUE)
                .header("X-User-Role", "ROLE_ADMIN"),
        )

        // then
        val opened = result
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andReturn()
        verify(exactly = 1) { members.get(7) }
        verify(exactly = 1) { streams.open(7, expiry, client) }
        assertThat(opened.request.getSession(false)).isNull()
        cleared()

        // A resumed dispatch loads the saved, same-request context. It must not parse a JWT again.
        opened.request.removeHeader("Authorization")
        emitter.complete()
        mvc.perform(asyncDispatch(opened))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andExpect(content().string(Matchers.containsString("event:ready")))
            .andExpect(content().string(Matchers.containsString("data:{}")))
        cleared()
        mvc.perform(get(STREAM)).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"))
        confirmVerified(members, streams, mutation, clock)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING", "INVALID", "NO_EXP"])
    fun `토큰이 없거나 잘못됐거나 만료가 없을 때__잘못된 클라이언트로 열면__바인딩 전에 인증 실패를 준다`(
        kind: String,
    ) {
        // given
        val request = get(STREAM).param("clientId", "invalid").header("X-Auth-Expires-At", Long.MAX_VALUE)
        if (kind != "MISSING") {
            request.header("Authorization", "Bearer " + (if (kind == "INVALID") "invalid" else token(null)))
        }
        val message = if (kind == "MISSING") {
            "인증 토큰이 필요합니다."
        } else if (kind == "INVALID") {
            "유효하지 않거나 만료된 토큰입니다."
        } else {
            "토큰 파싱에 실패했습니다."
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
        confirmVerified(members, streams, mutation, clock)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["*/*", "text/event-stream"])
    fun `잘못된 클라이언트와 검증된 토큰이 주어졌을 때__허용 미디어별로 열면__현재 회원 조회 전에 원래 400을 준다`(
        accept: String,
    ) {
        // given
        val request = get(STREAM)
            .param("clientId", "invalid")
            .accept(accept)
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
        if (accept == MediaType.TEXT_EVENT_STREAM_VALUE) {
            result.andExpect(content().string(""))
        } else {
            result.andExpect(
                content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT),
            )
        }
        confirmVerified(members, streams, mutation, clock)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["INACTIVE", "MEMBER", "RUNTIME"])
    fun `현재 회원 조회가 실패했을 때__열면__원인별 오류를 주고 등록하지 않는다`(kind: String) {
        // given
        if (kind == "INACTIVE") {
            every { members.get(any()) } returns ContactMember(7L, "", false, true)
        } else {
            every { members.get(any()) } throws if (kind == "MEMBER") {
                ContactMemberUnavailableException(TransactionSystemException("synthetic-private"))
            } else {
                IllegalStateException("synthetic-private")
            }
        }
        val message = if (kind == "INACTIVE") {
            "이용할 수 없는 회원입니다."
        } else if (kind == "MEMBER") {
            "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
        } else {
            "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."
        }

        // when
        val result = mvc.perform(get(STREAM).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))

        // then
        result
            .andExpect(status().`is`(if (kind == "INACTIVE") 403 else 503))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"" + message + "\"}", JsonCompareMode.STRICT))
        verify(exactly = 1) { members.get(7) }
        confirmVerified(members, streams, mutation, clock)
        cleared()
    }

    @Test
    fun `서명된 호출자와 위조 신원이 주어졌을 때__읽음 처리하면__빈 200을 주고 호출자 가드만 변경 포트로 실행한다`() {
        // given
        every { members.get(any()) } returns active()

        // This callback models entry order only. Real afterCommit/resync belongs to the JDBC/PG gate.
        every { mutation.read(any(), any(), any(), any()) } answers {
            thirdArg<Runnable>().run()
        }

        // when
        val result = mvc.perform(
            put(READ)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .header("X-User-Id", "999")
                .param("userId", "999"),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(content().string(""))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verify(exactly = 1) { mutation.read(7L, NOTE, any(), any()) }
        verify(exactly = 1) { members.get(7) }
        confirmVerified(mutation, members, streams, clock)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `깨진 읽음 UUID가 주어졌을 때__토큰이 없거나 유효하면__바인딩 전 인증을 유지하고 변경하지 않는다`(
        authenticated: Boolean,
    ) {
        // given
        val request = put("/api/v1/notes/not-a-uuid/read")
        if (authenticated) {
            request.header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
        }

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().`is`(if (authenticated) 400 else 401))
            .andExpect(header().doesNotExist("Set-Cookie"))
        confirmVerified(members, mutation, streams, clock)
        cleared()
    }

    @ParameterizedTest
    @MethodSource("readFailures")
    fun `변경 포트가 실패했을 때__읽음 처리하면__없음 회원 저장소 쓰기 런타임 정책을 유지한다`(
        failure: RuntimeException,
        status: Int,
        message: String,
    ) {
        // given
        if (failure is ContactMemberUnavailableException) {
            every { members.get(any()) } throws failure
        } else {
            every { members.get(any()) } returns active()
        }

        every { mutation.read(any(), any(), any(), any()) } answers {
            thirdArg<Runnable>().run()
            throw failure
        }

        // when
        val result = mvc.perform(put(READ).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))

        // then
        result
            .andExpect(status().`is`(status))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"" + message + "\"}", JsonCompareMode.STRICT))
        verify(exactly = 1) { mutation.read(7L, NOTE, any(), any()) }
        verify(exactly = 1) { members.get(7) }
        confirmVerified(mutation, members, streams, clock)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/notes/stream/extra",
            "POST /api/v1/notes/stream",
            "PUT /api/v1/notes/f2600000-0000-0000-0000-000000000001/read/extra",
        ],
    )
    fun `노출되지 않은 스트림이나 읽음 인접 경로일 때__요청하면__의존성 없이 기본 403을 유지한다`(wire: String) {
        // given
        val parts = wire.split(" ", limit = 2)

        // when
        val result = mvc.perform(
            request(HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Set-Cookie"))
        confirmVerified(members, mutation, streams, clock)
        cleared()
    }

    companion object {
        private const val STREAM = "/api/v1/notes/stream"
        private val NOTE: UUID = UUID.fromString("f2600000-0000-0000-0000-000000000001")
        private val READ = "/api/v1/notes/" + NOTE + "/read"

        @JvmStatic
        fun readFailures(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(NoteReadMutation.MissingOwnedNote(), 404, "쪽지를 찾을 수 없습니다."),
                Arguments.of(
                    ContactMemberUnavailableException(IllegalStateException("synthetic")),
                    503,
                    "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.",
                ),
                Arguments.of(
                    DataAccessResourceFailureException("synthetic"),
                    503,
                    "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.",
                ),
                Arguments.of(TransactionSystemException("synthetic"), 503, "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),
            )
        }

        private fun active(): ContactMember = ContactMember(7L, " 原文 ", true, false)

        private fun token(expiry: Instant?): String {
            val token = Jwts.builder().claim("userId", 7).claim("role", "ROLE_USER")
            if (expiry != null) {
                token.expiration(Date.from(expiry))
            }
            return token.signWith(F3TestInputs.KEY).compact()
        }

        private fun cleared() {
            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }
    }
}
