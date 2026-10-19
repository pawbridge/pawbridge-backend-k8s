package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.note.application.MarkPrivateNoteRead;
import com.pawbridge.backend.community.internal.note.application.NoteReadMutation;
import com.pawbridge.backend.community.internal.note.application.RequireActiveNoteStream;
import com.pawbridge.backend.community.internal.note.web.NoteReadMutationController;
import com.pawbridge.backend.community.internal.note.web.NoteReadMutationExceptionHandler;
import com.pawbridge.backend.community.internal.note.web.NoteStreamController;
import com.pawbridge.backend.community.internal.note.web.NoteStreamExceptionHandler;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteStreamRegistry;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import io.jsonwebtoken.Jwts;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Real MVC/security/async dispatch with a real Spring emitter; mutation and registry are mocked, not PG/socket evidence. */
@WebMvcTest(controllers = {NoteStreamController.class, NoteReadMutationController.class},
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {NoteStreamController.class, NoteStreamExceptionHandler.class, RequireActiveNoteStream.class,
        NoteReadMutationController.class, NoteReadMutationExceptionHandler.class, MarkPrivateNoteRead.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class NoteReadStreamHttpTest {
    private static final String STREAM = "/api/v1/notes/stream";
    private static final UUID NOTE = UUID.fromString("f2600000-0000-0000-0000-000000000001");
    private static final String READ = "/api/v1/notes/" + NOTE + "/read";
    @Autowired MockMvc mvc;
    @MockitoBean ContactMemberQuery members;
    @MockitoBean NoteReadMutation mutation;
    @MockitoBean PrivateNoteStreamRegistry streams;
    @MockitoBean Clock clock;

    @BeforeEach void clearOnlyContainerLifecycleProbes() {
        // Spring may inspect this SmartLifecycle mock while creating the test context.
        clearInvocations(streams);
    }

    @Test void givenVerifiedExpiryAndSpoofedHeaders__whenOpeningAndCompletingAsync__thenKeepCallerExpiryReadyAndRequestOnlyAuthenticationWithoutSession() throws Exception {
        var expiry = Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.SECONDS);
        var client = UUID.randomUUID(); var emitter = new SseEmitter(120_000L);
        emitter.send(SseEmitter.event().name("ready").data("{}"));
        when(members.get(7)).thenReturn(active()); when(streams.open(7, expiry, client)).thenReturn(emitter);

        var opened = mvc.perform(get(STREAM).accept(MediaType.TEXT_EVENT_STREAM).param("clientId", client.toString())
                        .header("Authorization", "Bearer " + token(expiry)).header("X-User-Id", "999")
                        .header("X-Auth-Expires-At", Long.MAX_VALUE).header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isOk()).andExpect(request().asyncStarted())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(header().doesNotExist("Set-Cookie")).andReturn();
        verify(members).get(7); verify(streams).open(7, expiry, client); verifyNoInteractions(mutation, clock);
        assertThat(opened.getRequest().getSession(false)).isNull(); cleared();

        // A resumed dispatch loads the saved, same-request context. It must not parse a JWT again.
        opened.getRequest().removeHeader("Authorization"); emitter.complete();
        mvc.perform(asyncDispatch(opened)).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:ready")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data:{}")));
        cleared();
        mvc.perform(get(STREAM)).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoMoreInteractions(members, streams); cleared();
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING", "INVALID", "NO_EXP"})
    void givenMissingBadOrExpirylessTokenAndInvalidClient__whenOpening__thenAuthenticateBeforeBindingMemberOrRegistry(String kind) throws Exception {
        var request = get(STREAM).param("clientId", "invalid").header("X-Auth-Expires-At", Long.MAX_VALUE);
        if (!kind.equals("MISSING")) request.header("Authorization", "Bearer " + (kind.equals("INVALID") ? "invalid" : token(null)));
        String message = kind.equals("MISSING") ? "인증 토큰이 필요합니다."
                : kind.equals("INVALID") ? "유효하지 않거나 만료된 토큰입니다." : "토큰 파싱에 실패했습니다.";
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(members, streams, mutation, clock); cleared();
    }

    @ParameterizedTest @ValueSource(strings = {"*/*", "text/event-stream"})
    void givenInvalidClientAndVerifiedToken__whenOpeningAcrossAcceptedMedia__thenReturnOriginal400NoStoreBeforeCurrentLookup(String accept) throws Exception {
        var result = mvc.perform(get(STREAM).param("clientId", "invalid").accept(accept)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        if (accept.equals(MediaType.TEXT_EVENT_STREAM_VALUE)) result.andExpect(content().string(""));
        else result.andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT));
        verifyNoInteractions(members, streams, mutation, clock); cleared();
    }

    @ParameterizedTest @ValueSource(strings = {"INACTIVE", "MEMBER", "RUNTIME"})
    void givenCurrentMemberFailure__whenOpening__thenReturnSourceSpecificErrorAndNeverRegister(String kind) throws Exception {
        if (kind.equals("INACTIVE")) when(members.get(7)).thenReturn(new ContactMember(7L, "", false, true));
        else when(members.get(7)).thenThrow(kind.equals("MEMBER")
                ? new ContactMemberUnavailableException(new TransactionSystemException("synthetic-private"))
                : new IllegalStateException("synthetic-private"));
        String message = kind.equals("INACTIVE") ? "이용할 수 없는 회원입니다."
                : kind.equals("MEMBER") ? "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요." : "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
        mvc.perform(get(STREAM).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().is(kind.equals("INACTIVE") ? 403 : 503)).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"" + message + "\"}", JsonCompareMode.STRICT));
        verify(members).get(7); verifyNoMoreInteractions(members); verifyNoInteractions(streams, mutation, clock); cleared();
    }

    @Test void givenSignedCallerAndSpoofedIdentity__whenMarkingRead__thenReturnEmpty200AndRunOnlyCallerGuardThroughMutationPort() throws Exception {
        when(members.get(7)).thenReturn(active());
        // This callback models entry order only. Real afterCommit/resync belongs to the JDBC/PG gate.
        doAnswer(call -> { call.getArgument(2, Runnable.class).run(); return null; })
                .when(mutation).read(eq(7L), eq(NOTE), any(Runnable.class), any());
        mvc.perform(put(READ).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .header("X-User-Id", "999").param("userId", "999"))
                .andExpect(status().isOk()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"));
        verify(mutation).read(eq(7L), eq(NOTE), any(Runnable.class), any()); verify(members).get(7);
        verifyNoMoreInteractions(mutation, members); verifyNoInteractions(streams, clock); cleared();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void givenMalformedReadUuid__whenTokenIsAbsentOrValid__thenKeepAuthenticationBeforeBindingAndNoMutation(boolean authenticated) throws Exception {
        var request = put("/api/v1/notes/not-a-uuid/read");
        if (authenticated) request.header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"));
        mvc.perform(request).andExpect(status().is(authenticated ? 400 : 401)).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(members, mutation, streams, clock); cleared();
    }

    @ParameterizedTest @MethodSource("readFailures")
    void givenMutationFailure__whenReading__thenKeepNotFoundMemberStorageAndWriterRuntimePolicies(RuntimeException failure, int status, String message) throws Exception {
        if (failure instanceof ContactMemberUnavailableException) when(members.get(7)).thenThrow(failure);
        else when(members.get(7)).thenReturn(active());
        doAnswer(call -> { call.getArgument(2, Runnable.class).run(); throw failure; })
                .when(mutation).read(eq(7L), eq(NOTE), any(Runnable.class), any());
        mvc.perform(put(READ).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().is(status)).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"" + message + "\"}", JsonCompareMode.STRICT));
        verify(mutation).read(eq(7L), eq(NOTE), any(Runnable.class), any()); verify(members).get(7);
        verifyNoMoreInteractions(mutation, members); verifyNoInteractions(streams, clock); cleared();
    }

    static Stream<Arguments> readFailures() {
        return Stream.of(
                Arguments.of(new NoteReadMutation.MissingOwnedNote(), 404, "쪽지를 찾을 수 없습니다."),
                Arguments.of(new ContactMemberUnavailableException(new IllegalStateException("synthetic")), 503, "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."),
                Arguments.of(new DataAccessResourceFailureException("synthetic"), 503, "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
                Arguments.of(new TransactionSystemException("synthetic"), 503, "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."));
    }

    @ParameterizedTest @ValueSource(strings = {"GET /api/v1/notes/stream/extra", "POST /api/v1/notes/stream", "PUT /api/v1/notes/f2600000-0000-0000-0000-000000000001/read/extra"})
    void givenUnexposedStreamOrReadNeighbor__whenRequesting__thenKeepDefault403WithoutDependencies(String wire) throws Exception {
        String[] parts = wire.split(" ", 2);
        mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(members, mutation, streams, clock); cleared();
    }

    private static ContactMember active() { return new ContactMember(7L, " 原文 ", true, false); }
    private static String token(Instant expiry) {
        var token = Jwts.builder().claim("userId", 7).claim("role", "ROLE_USER");
        if (expiry != null) token.expiration(Date.from(expiry));
        return token.signWith(F3TestInputs.KEY).compact();
    }
    private static void cleared() { assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull(); }
}
