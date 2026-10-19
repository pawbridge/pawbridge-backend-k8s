package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader;
import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader;
import com.pawbridge.backend.community.internal.note.application.ReadPrivateNote;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteReadController;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteReadExceptionHandler;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.F22LegacyPrivateNoteController;
import com.pawbridge.communityservice.contact.F22LegacyPrivateNoteExceptionAdvice;
import com.pawbridge.communityservice.contact.F22LegacyPrivateNoteService;
import com.pawbridge.communityservice.contact.PrivateNoteHttpBoundary;
import com.pawbridge.communityservice.contact.PrivateNoteModels;
import com.pawbridge.communityservice.contact.PrivateNoteRepository;
import com.pawbridge.communityservice.exception.common.GlobalExceptionRestAdvice;
import feign.FeignException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;

/** Original-only UUID path observations pin the comparison expectations without candidate inference. */
class F22LegacyPrivateNoteMvcTest {
    private static final String FIRST = "f22abcde-1234-5678-9abc-123456789abc";
    private static final String SECOND = "f22abcde-1234-5678-9abc-123456789abd";
    private static final Instant NOW = Instant.parse("2031-01-01T00:00:00.123456Z");
    private static final Instant CREATED = Instant.parse("2030-12-31T15:00:00.123456Z");
    private static final String RAW_BODY = "  <b>synthetic private body</b>\nraw text  ";

    @ParameterizedTest
    @MethodSource("paths")
    void givenMeasuredOriginalUuidPath__whenBothMvcBind__thenWireBoundIdAndFailurePriorityAgree(
            String label, String rawPath, MeasuredPath expected) throws Exception {
        assertThat(expected).as("UNMEASURED F22 original UUID path '%s': fill only from original-only observation", label)
                .isNotNull();
        var parameter = F22LegacyPrivateNoteController.class.getMethod("get", long.class, UUID.class).getParameters()[1];
        var annotation = parameter.getAnnotation(PathVariable.class);
        assertThat(parameter.getType()).isEqualTo(UUID.class);
        assertThat(annotation.value()).isEqualTo("id");
        assertThat(annotation.required()).isTrue();
        var client = mock(UserServiceClient.class);
        var repository = mock(PrivateNoteRepository.class);
        var sourceClock = mock(Clock.class);
        when(client.getContactMember(7L)).thenReturn(new PrivateNoteModels.ContactMember(7L, "synthetic", true, false));
        when(sourceClock.instant()).thenReturn(NOW);
        when(repository.find(eq(7L), nullable(UUID.class), eq(NOW))).thenReturn(Optional.empty());
        var source = spy(new F22LegacyPrivateNoteService(repository, client, sourceClock));
        var sourceReached = new AtomicBoolean();
        var sourceId = new AtomicReference<UUID>();
        doAnswer(call -> {
            sourceReached.set(true);
            sourceId.set(call.getArgument(1, UUID.class));
            return call.callRealMethod();
        }).when(source).get(eq(7L), nullable(UUID.class));
        var members = mock(ContactMemberQuery.class);
        var reader = mock(PrivateNoteReader.class);
        var blocks = mock(NoteBlockReader.class);
        var candidateClock = mock(Clock.class);
        when(members.get(7L)).thenReturn(new ContactMember(7L, "synthetic", true, false));
        when(candidateClock.instant()).thenReturn(NOW);
        when(reader.find(eq(7L), nullable(UUID.class), eq(NOW))).thenReturn(Optional.empty());
        var candidate = spy(new ReadPrivateNote(members, reader, blocks, candidateClock));
        var candidateReached = new AtomicBoolean();
        var candidateId = new AtomicReference<UUID>();
        doAnswer(call -> {
            candidateReached.set(true);
            candidateId.set(call.getArgument(1, UUID.class));
            return call.callRealMethod();
        }).when(candidate).read(eq(7L), nullable(UUID.class));
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            var before = request(original(source, mapper), rawPath);
            authenticate();
            var after = request(candidate(candidate, mapper), rawPath);
            assertThat(before.getResponse().getStatus()).as(label).isEqualTo(expected.status());
            assertThat(after.getResponse().getStatus()).as(label).isEqualTo(expected.status());
            var wire = mapper.readTree(before.getResponse().getContentAsByteArray());
            assertThat(wire).isEqualTo(mapper.readTree(expected.body()));
            assertThat(mapper.readTree(after.getResponse().getContentAsByteArray())).isEqualTo(wire);
            assertThat(sourceReached.get()).isEqualTo(expected.reached());
            assertThat(candidateReached.get()).isEqualTo(expected.reached());
            assertThat(sourceId.get()).isEqualTo(expected.noteId());
            assertThat(candidateId.get()).isEqualTo(expected.noteId());
            assertThat(calls(client, "getContactMember")).isEqualTo(expected.singleCalls());
            assertThat(calls(members, "get")).isEqualTo(expected.singleCalls());
            assertThat(calls(repository, "find")).isEqualTo(expected.findCalls());
            assertThat(calls(reader, "find")).isEqualTo(expected.findCalls());
            assertThat(calls(repository, "blocked")).isEqualTo(expected.blockCalls());
            assertThat(calls(blocks, "blocked")).isEqualTo(expected.blockCalls());
            assertThat(calls(sourceClock, "instant")).isEqualTo(expected.clockCalls());
            assertThat(calls(candidateClock, "instant")).isEqualTo(expected.clockCalls());
            assertThat(mockingDetails(client).getInvocations()).hasSize((int) expected.singleCalls());
            assertThat(mockingDetails(repository).getInvocations()).hasSize((int) (expected.findCalls() + expected.blockCalls()));
            assertThat(mockingDetails(members).getInvocations()).hasSize((int) expected.singleCalls());
            assertThat(mockingDetails(reader).getInvocations()).hasSize((int) expected.findCalls());
            assertThat(mockingDetails(blocks).getInvocations()).hasSize((int) expected.blockCalls());
            cache(before);
            cache(after);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"inbox-active", "inbox-blocked", "sent-null", "inbox-pending"})
    void givenStoredPrivateNoteAndCounterpart__whenBothMvcSerialize__thenKeepExactTenFieldsRawValuesAndMicros(String scenario) throws Exception {
        boolean sent = scenario.equals("sent-null");
        boolean pending = scenario.equals("inbox-pending");
        boolean blocked = scenario.equals("inbox-blocked");
        boolean active = !sent && !pending;
        long counterpart = Long.MAX_VALUE;
        Long sender = sent ? 7L : counterpart;
        Long recipient = sent ? null : 7L;
        String direction = sent ? "SENT" : "INBOX";
        String contextType = sent ? null : blocked ? "REPORT" : "POST";
        Long contextId = sent ? null : Long.MAX_VALUE;
        Instant readAt = scenario.equals("inbox-active") ? null : CREATED.plusNanos(1000);
        boolean favorite = !scenario.equals("inbox-active");
        UUID id = UUID.fromString(FIRST);
        var client = mock(UserServiceClient.class);
        var repository = mock(PrivateNoteRepository.class);
        var sourceClock = mock(Clock.class);
        when(client.getContactMember(7L)).thenReturn(new PrivateNoteModels.ContactMember(7L, "synthetic", true, false));
        when(client.getContactMember(counterpart)).thenReturn(new PrivateNoteModels.ContactMember(counterpart, "  ", !pending, pending));
        when(sourceClock.instant()).thenReturn(NOW);
        when(repository.find(7L, id, NOW)).thenReturn(Optional.of(new PrivateNoteModels.Note(id, sender, recipient,
                RAW_BODY, null, contextType, contextId, CREATED, NOW.plusSeconds(60), direction, readAt, favorite)));
        when(repository.blocked(7L, counterpart)).thenReturn(blocked);
        var members = mock(ContactMemberQuery.class);
        var reader = mock(PrivateNoteReader.class);
        var blocks = mock(NoteBlockReader.class);
        var candidateClock = mock(Clock.class);
        when(members.get(7L)).thenReturn(new ContactMember(7L, "synthetic", true, false));
        when(members.get(counterpart)).thenReturn(new ContactMember(counterpart, "  ", !pending, pending));
        when(candidateClock.instant()).thenReturn(NOW);
        when(reader.find(7L, id, NOW)).thenReturn(Optional.of(new PrivateNoteReader.StoredNote(id, sender, recipient,
                RAW_BODY, contextType, contextId, CREATED, direction, readAt, favorite)));
        when(blocks.blocked(7L, counterpart)).thenReturn(blocked);
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            var before = request(original(new F22LegacyPrivateNoteService(repository, client, sourceClock), mapper), FIRST);
            authenticate();
            var after = request(candidate(new ReadPrivateNote(members, reader, blocks, candidateClock), mapper), FIRST);
            assertThat(before.getResponse().getStatus()).isEqualTo(200);
            assertThat(after.getResponse().getStatus()).isEqualTo(200);
            var expected = mapper.createObjectNode().put("code", 200).put("message", "요청이 성공적으로 처리되었습니다.");
            var data = expected.putObject("data");
            data.put("noteId", FIRST).put("body", RAW_BODY).put("direction", direction)
                    .put("createdAt", "2030-12-31T15:00:00.123456Z").put("favorite", favorite)
                    .put("counterpartNickname", active ? "  " : "탈퇴한 회원").put("canReply", active && !blocked);
            if (readAt == null) data.putNull("readAt"); else data.put("readAt", "2030-12-31T15:00:00.123457Z");
            if (active) data.put("counterpartId", counterpart); else data.putNull("counterpartId");
            if (contextType == null) data.putNull("contextHref");
            else data.put("contextHref", (contextType.equals("POST") ? "/community/" : "/reports/") + contextId);
            var wire = mapper.readTree(before.getResponse().getContentAsByteArray());
            assertThat(wire).isEqualTo(expected);
            assertThat(mapper.readTree(after.getResponse().getContentAsByteArray())).isEqualTo(wire);
            assertThat(wire.size()).isEqualTo(3);
            assertThat(wire.path("data").size()).isEqualTo(10);
            if (active) assertThat(wire.path("data").path("counterpartId").longValue()).isEqualTo(counterpart);
            var sourceOrder = inOrder(client, sourceClock, repository);
            sourceOrder.verify(client).getContactMember(7L);
            sourceOrder.verify(sourceClock).instant();
            sourceOrder.verify(repository).find(7L, id, NOW);
            if (!sent) sourceOrder.verify(client).getContactMember(counterpart);
            if (active) sourceOrder.verify(repository).blocked(7L, counterpart);
            var candidateOrder = inOrder(members, candidateClock, reader, blocks);
            candidateOrder.verify(members).get(7L);
            candidateOrder.verify(candidateClock).instant();
            candidateOrder.verify(reader).find(7L, id, NOW);
            if (!sent) candidateOrder.verify(members).get(counterpart);
            if (active) candidateOrder.verify(blocks).blocked(7L, counterpart);
            verifyNoMoreInteractions(client, sourceClock, repository, members, candidateClock, reader, blocks);
            cache(before);
            cache(after);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "member", "storage", "runtime"})
    void givenOriginalFailurePhase__whenBothMvcHandle__thenSameSafeMessageOnlyStatusAndNoStore(String phase) throws Exception {
        UUID id = UUID.fromString(FIRST);
        var client = mock(UserServiceClient.class);
        var repository = mock(PrivateNoteRepository.class);
        var sourceClock = mock(Clock.class);
        var members = mock(ContactMemberQuery.class);
        var reader = mock(PrivateNoteReader.class);
        var blocks = mock(NoteBlockReader.class);
        var candidateClock = mock(Clock.class);
        when(client.getContactMember(7L)).thenReturn(new PrivateNoteModels.ContactMember(7L, "synthetic", !phase.equals("inactive"), phase.equals("inactive")));
        when(members.get(7L)).thenReturn(new ContactMember(7L, "synthetic", !phase.equals("inactive"), phase.equals("inactive")));
        when(sourceClock.instant()).thenReturn(NOW);
        when(candidateClock.instant()).thenReturn(NOW);
        if (phase.equals("member")) {
            var unavailable = mock(FeignException.class);
            when(unavailable.status()).thenReturn(500);
            when(client.getContactMember(7L)).thenThrow(unavailable);
            when(members.get(7L)).thenThrow(new ContactMemberUnavailableException(new IllegalStateException("synthetic")));
        } else if (phase.equals("storage")) {
            when(repository.find(7L, id, NOW)).thenThrow(new DataRetrievalFailureException("synthetic"));
            when(reader.find(7L, id, NOW)).thenThrow(new DataRetrievalFailureException("synthetic"));
        } else if (phase.equals("runtime")) {
            when(sourceClock.instant()).thenThrow(new IllegalStateException("synthetic"));
            when(candidateClock.instant()).thenThrow(new IllegalStateException("synthetic"));
        }
        String message = switch (phase) {
            case "inactive" -> "이용할 수 없는 회원입니다.";
            case "member" -> "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.";
            case "storage" -> "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.";
            default -> "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
        };
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            var before = request(original(new F22LegacyPrivateNoteService(repository, client, sourceClock), mapper), FIRST);
            authenticate();
            var after = request(candidate(new ReadPrivateNote(members, reader, blocks, candidateClock), mapper), FIRST);
            int status = phase.equals("inactive") ? 403 : 503;
            assertThat(before.getResponse().getStatus()).isEqualTo(status);
            assertThat(after.getResponse().getStatus()).isEqualTo(status);
            var expected = mapper.createObjectNode().put("message", message);
            assertThat(mapper.readTree(before.getResponse().getContentAsByteArray())).isEqualTo(expected);
            assertThat(mapper.readTree(after.getResponse().getContentAsByteArray())).isEqualTo(expected);
            int clocks = phase.equals("inactive") || phase.equals("member") ? 0 : 1;
            int finds = phase.equals("storage") ? 1 : 0;
            assertThat(mockingDetails(client).getInvocations()).hasSize(1);
            assertThat(mockingDetails(members).getInvocations()).hasSize(1);
            assertThat(mockingDetails(sourceClock).getInvocations()).hasSize(clocks);
            assertThat(mockingDetails(candidateClock).getInvocations()).hasSize(clocks);
            assertThat(mockingDetails(repository).getInvocations()).hasSize(finds);
            assertThat(mockingDetails(reader).getInvocations()).hasSize(finds);
            assertThat(mockingDetails(blocks).getInvocations()).isEmpty();
            cache(before);
            cache(after);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private MockMvc original(F22LegacyPrivateNoteService service, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new F22LegacyPrivateNoteController(service))
                .setControllerAdvice(new F22LegacyPrivateNoteExceptionAdvice(), new GlobalExceptionRestAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new PrivateNoteHttpBoundary()).build();
    }

    private MockMvc candidate(ReadPrivateNote service, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new PrivateNoteReadController(service))
                .setControllerAdvice(new PrivateNoteReadExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    private MvcResult request(MockMvc mvc, String path) throws Exception {
        return mvc.perform(get("/api/v1/notes/" + path).header("X-User-Id", "7")).andReturn();
    }

    private static void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L, null, List.of()));
    }

    private void cache(MvcResult result) {
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }

    private static long calls(Object mock, String method) {
        return mockingDetails(mock).getInvocations().stream().filter(call -> call.getMethod().getName().equals(method)).count();
    }

    private record MeasuredPath(int status, boolean reached, UUID noteId, String body,
                                long singleCalls, long findCalls, long blockCalls, long clockCalls) {
    }

    static Stream<Arguments> paths() {
        // Original-only path observation SOURCE cba94f898399674d4f708ce0c01e2192a18bc6c7.
        // Observation SHA-256: 3ca4e11ad812b3bce2906f077ce8a18a0b1277776574ec3316fb338e6a89c5c0
        return Stream.of(Arguments.of("canonical", FIRST, new MeasuredPath(404, true, UUID.fromString("f22abcde-1234-5678-9abc-123456789abc"), "{\"message\":\"\ucabd\uc9c0\ub97c \ucc3e\uc744 \uc218 \uc5c6\uc2b5\ub2c8\ub2e4.\"}", 1L, 1L, 0L, 1L)),
                Arguments.of("uppercase", FIRST.toUpperCase(Locale.ROOT), new MeasuredPath(404, true, UUID.fromString("f22abcde-1234-5678-9abc-123456789abc"), "{\"message\":\"\ucabd\uc9c0\ub97c \ucc3e\uc744 \uc218 \uc5c6\uc2b5\ub2c8\ub2e4.\"}", 1L, 1L, 0L, 1L)),
                Arguments.of("shortened", "1-1-1-1-1", new MeasuredPath(404, true, UUID.fromString("00000001-0001-0001-0001-000000000001"), "{\"message\":\"\ucabd\uc9c0\ub97c \ucc3e\uc744 \uc218 \uc5c6\uc2b5\ub2c8\ub2e4.\"}", 1L, 1L, 0L, 1L)),
                Arguments.of("invalid", "not-a-uuid", new MeasuredPath(400, false, null, "{\"message\":\"\uc694\uccad \ud615\uc2dd\uacfc \ub85c\uadf8\uc778 \uc815\ubcf4\ub97c \ud655\uc778\ud574 \uc8fc\uc138\uc694.\"}", 0L, 0L, 0L, 0L)),
                Arguments.of("overflow-component", "100000000-0000-0000-0000-000000000000", new MeasuredPath(400, false, null, "{\"message\":\"\uc694\uccad \ud615\uc2dd\uacfc \ub85c\uadf8\uc778 \uc815\ubcf4\ub97c \ud655\uc778\ud574 \uc8fc\uc138\uc694.\"}", 0L, 0L, 0L, 0L)),
                Arguments.of("csv", FIRST + "," + SECOND, new MeasuredPath(400, false, null, "{\"message\":\"\uc694\uccad \ud615\uc2dd\uacfc \ub85c\uadf8\uc778 \uc815\ubcf4\ub97c \ud655\uc778\ud574 \uc8fc\uc138\uc694.\"}", 0L, 0L, 0L, 0L)));
    }
}
