package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.note.application.MarkPrivateNoteRead
import com.pawbridge.backend.community.internal.note.application.NoteReadMutation
import com.pawbridge.backend.community.internal.note.application.RequireActiveNoteStream
import com.pawbridge.backend.community.internal.note.web.NoteReadMutationController
import com.pawbridge.backend.community.internal.note.web.NoteReadMutationExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteStreamController
import com.pawbridge.backend.community.internal.note.web.NoteStreamExceptionHandler
import com.pawbridge.backend.community.internal.note.web.PrivateNoteStreamRegistry
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import io.jsonwebtoken.Jwts
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.clearInvocations
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
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
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

    @MockitoBean
    lateinit var members: ContactMemberQuery

    @MockitoBean
    lateinit var mutation: NoteReadMutation

    @MockitoBean
    lateinit var streams: PrivateNoteStreamRegistry

    @MockitoBean
    lateinit var clock: Clock

    @BeforeEach
    fun clearOnlyContainerLifecycleProbes() {
        // Spring may inspect this SmartLifecycle mock while creating the test context.
        clearInvocations(streams)
    }

    @Test
    fun givenVerifiedExpiryAndSpoofedHeaders__whenOpeningAndCompletingAsync__thenKeepCallerExpiryReadyAndRequestOnlyAuthenticationWithoutSession() {
        val expiry = Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.SECONDS)
        val client = UUID.randomUUID()
        val emitter = SseEmitter(120_000L)
        emitter.send(SseEmitter.event().name("ready").data("{}"))
        whenever(members.get(7)).thenReturn(active())
        whenever(streams.open(7, expiry, client)).thenReturn(emitter)

        val opened = mvc.perform(
            get(STREAM)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .param("clientId", client.toString())
                .header("Authorization", "Bearer " + token(expiry))
                .header("X-User-Id", "999")
                .header("X-Auth-Expires-At", Long.MAX_VALUE)
                .header("X-User-Role", "ROLE_ADMIN"),
        )
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andReturn()
        verify(members).get(7)
        verify(streams).open(7, expiry, client)
        verifyNoInteractions(mutation, clock)
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
        verifyNoMoreInteractions(members, streams)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING", "INVALID", "NO_EXP"])
    fun givenMissingBadOrExpirylessTokenAndInvalidClient__whenOpening__thenAuthenticateBeforeBindingMemberOrRegistry(
        kind: String,
    ) {
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
        mvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(members, streams, mutation, clock)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["*/*", "text/event-stream"])
    fun givenInvalidClientAndVerifiedToken__whenOpeningAcrossAcceptedMedia__thenReturnOriginal400NoStoreBeforeCurrentLookup(
        accept: String,
    ) {
        val result = mvc.perform(
            get(STREAM)
                .param("clientId", "invalid")
                .accept(accept)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
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
        verifyNoInteractions(members, streams, mutation, clock)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["INACTIVE", "MEMBER", "RUNTIME"])
    fun givenCurrentMemberFailure__whenOpening__thenReturnSourceSpecificErrorAndNeverRegister(kind: String) {
        if (kind == "INACTIVE") {
            whenever(members.get(7)).thenReturn(ContactMember(7L, "", false, true))
        } else {
            whenever(members.get(7)).thenThrow(
                if (kind == "MEMBER") {
                    ContactMemberUnavailableException(TransactionSystemException("synthetic-private"))
                } else {
                    IllegalStateException("synthetic-private")
                },
            )
        }
        val message = if (kind == "INACTIVE") {
            "이용할 수 없는 회원입니다."
        } else if (kind == "MEMBER") {
            "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
        } else {
            "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."
        }
        mvc.perform(get(STREAM).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
            .andExpect(status().`is`(if (kind == "INACTIVE") 403 else 503))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"" + message + "\"}", JsonCompareMode.STRICT))
        verify(members).get(7)
        verifyNoMoreInteractions(members)
        verifyNoInteractions(streams, mutation, clock)
        cleared()
    }

    @Test
    fun givenSignedCallerAndSpoofedIdentity__whenMarkingRead__thenReturnEmpty200AndRunOnlyCallerGuardThroughMutationPort() {
        whenever(members.get(7)).thenReturn(active())
        // This callback models entry order only. Real afterCommit/resync belongs to the JDBC/PG gate.
        doAnswer(
            Answer<Any?> { call ->
                call.getArgument(2, Runnable::class.java).run()
                null
            },
        ).`when`(mutation).read(eq(7L), eq(NOTE), anyNonNull<Runnable>(), anyNonNull<Supplier<Instant>>())
        mvc.perform(
            put(READ)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .header("X-User-Id", "999")
                .param("userId", "999"),
        )
            .andExpect(status().isOk())
            .andExpect(content().string(""))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verify(mutation).read(eq(7L), eq(NOTE), anyNonNull<Runnable>(), anyNonNull<Supplier<Instant>>())
        verify(members).get(7)
        verifyNoMoreInteractions(mutation, members)
        verifyNoInteractions(streams, clock)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun givenMalformedReadUuid__whenTokenIsAbsentOrValid__thenKeepAuthenticationBeforeBindingAndNoMutation(
        authenticated: Boolean,
    ) {
        val request = put("/api/v1/notes/not-a-uuid/read")
        if (authenticated) {
            request.header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
        }
        mvc.perform(request)
            .andExpect(status().`is`(if (authenticated) 400 else 401))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verifyNoInteractions(members, mutation, streams, clock)
        cleared()
    }

    @ParameterizedTest
    @MethodSource("readFailures")
    fun givenMutationFailure__whenReading__thenKeepNotFoundMemberStorageAndWriterRuntimePolicies(
        failure: RuntimeException,
        status: Int,
        message: String,
    ) {
        if (failure is ContactMemberUnavailableException) {
            whenever(members.get(7)).thenThrow(failure)
        } else {
            whenever(members.get(7)).thenReturn(active())
        }
        doAnswer(
            Answer<Any?> { call ->
                call.getArgument(2, Runnable::class.java).run()
                throw failure
            },
        ).`when`(mutation).read(eq(7L), eq(NOTE), anyNonNull<Runnable>(), anyNonNull<Supplier<Instant>>())
        mvc.perform(put(READ).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
            .andExpect(status().`is`(status))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"" + message + "\"}", JsonCompareMode.STRICT))
        verify(mutation).read(eq(7L), eq(NOTE), anyNonNull<Runnable>(), anyNonNull<Supplier<Instant>>())
        verify(members).get(7)
        verifyNoMoreInteractions(mutation, members)
        verifyNoInteractions(streams, clock)
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
    fun givenUnexposedStreamOrReadNeighbor__whenRequesting__thenKeepDefault403WithoutDependencies(wire: String) {
        val parts = wire.split(" ", limit = 2)
        mvc.perform(
            request(HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Set-Cookie"))
        verifyNoInteractions(members, mutation, streams, clock)
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
