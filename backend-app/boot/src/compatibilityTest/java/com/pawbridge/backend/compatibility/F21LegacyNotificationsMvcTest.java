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
import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader;
import com.pawbridge.backend.community.internal.note.application.ReadNoteNotifications;
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsController;
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsExceptionHandler;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.F21LegacyNotificationsController;
import com.pawbridge.communityservice.contact.F21LegacyNotificationsExceptionAdvice;
import com.pawbridge.communityservice.contact.F21LegacyNotificationsService;
import com.pawbridge.communityservice.contact.PrivateNoteHttpBoundary;
import com.pawbridge.communityservice.contact.PrivateNoteModels;
import com.pawbridge.communityservice.contact.PrivateNoteRepository;
import com.pawbridge.communityservice.exception.common.GlobalExceptionRestAdvice;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;

/** Original MVC binding observations pinned to the measured source; candidate comparison does not infer expectations. */
class F21LegacyNotificationsMvcTest {
    private static final String FIRST = "f2100000-0000-0000-0000-000000000001";
    private static final String SECOND = "f2100000-0000-0000-0000-000000000002";
    private static final Instant NOW = Instant.parse("2031-01-01T00:00:00.123456Z");

    @ParameterizedTest
    @MethodSource("bindings")
    void givenMeasuredOriginalOptionalUuidQuery__whenBothMvcBind__thenCursorWireCallsAndFailurePriorityAgree(
            String label, String[] values, MeasuredBinding expected) throws Exception {
        assertThat(expected).as("UNMEASURED F21 original UUID binding '%s': fill only from original-only observations before comparison", label)
                .isNotNull();
        var parameter = F21LegacyNotificationsController.class
                .getMethod("notifications", long.class, UUID.class).getParameters()[1];
        assertThat(parameter.isNamePresent()).isTrue();
        assertThat(parameter.getName()).isEqualTo("cursor");
        var annotation = parameter.getAnnotation(RequestParam.class);
        assertThat(annotation.name()).isEmpty();
        assertThat(annotation.value()).isEmpty();
        assertThat(annotation.required()).isFalse();
        assertThat(annotation.defaultValue()).isEqualTo(ValueConstants.DEFAULT_NONE);

        var client = mock(UserServiceClient.class);
        var repository = mock(PrivateNoteRepository.class);
        var sourceClock = mock(Clock.class);
        when(client.getContactMember(7L)).thenReturn(new PrivateNoteModels.ContactMember(7L, "synthetic", true, false));
        when(sourceClock.instant()).thenReturn(NOW);
        when(repository.notifications(eq(7L), nullable(UUID.class), eq(NOW))).thenReturn(List.of());
        when(repository.unread(7L, NOW)).thenReturn(3L);
        var original = spy(new F21LegacyNotificationsService(repository, client, sourceClock));
        var sourceReached = new AtomicBoolean();
        var sourceCursor = new AtomicReference<UUID>();
        doAnswer(call -> {
            sourceReached.set(true);
            sourceCursor.set(call.getArgument(1, UUID.class));
            return call.callRealMethod();
        }).when(original).notifications(eq(7L), nullable(UUID.class));

        var members = mock(ContactMemberQuery.class);
        var reader = mock(NoteNotificationReader.class);
        var batches = mock(ContactMembersQuery.class);
        var candidateClock = mock(Clock.class);
        when(members.get(7L)).thenReturn(new ContactMember(7L, "synthetic", true, false));
        when(candidateClock.instant()).thenReturn(NOW);
        when(reader.read(eq(7L), nullable(UUID.class), eq(NOW))).thenReturn(List.of());
        when(reader.unread(7L, NOW)).thenReturn(3L);
        var candidate = spy(new ReadNoteNotifications(members, reader, batches, candidateClock));
        var candidateReached = new AtomicBoolean();
        var candidateCursor = new AtomicReference<UUID>();
        doAnswer(call -> {
            candidateReached.set(true);
            candidateCursor.set(call.getArgument(1, UUID.class));
            return call.callRealMethod();
        }).when(candidate).read(eq(7L), nullable(UUID.class));

        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            var before = request(original(original, mapper), values);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L, null, List.of()));
            var after = request(candidate(candidate, mapper), values);
            assertThat(before.getResponse().getStatus()).as(label).isEqualTo(expected.status());
            assertThat(after.getResponse().getStatus()).as(label).isEqualTo(expected.status());
            var wire = mapper.readTree(before.getResponse().getContentAsByteArray());
            assertThat(wire).as(label).isEqualTo(mapper.readTree(expected.body()));
            assertThat(mapper.readTree(after.getResponse().getContentAsByteArray())).as(label).isEqualTo(wire);
            assertThat(sourceReached.get()).isEqualTo(expected.reached());
            assertThat(candidateReached.get()).isEqualTo(expected.reached());
            assertThat(sourceCursor.get()).isEqualTo(expected.cursor());
            assertThat(candidateCursor.get()).isEqualTo(expected.cursor());
            assertThat(calls(client, "getContactMember")).isEqualTo(expected.currentCalls());
            assertThat(calls(client, "getContactMembers")).isEqualTo(expected.batchCalls());
            assertThat(calls(repository, "notifications")).isEqualTo(expected.listCalls());
            assertThat(calls(repository, "unread")).isEqualTo(expected.unreadCalls());
            assertThat(calls(sourceClock, "instant")).isEqualTo(expected.clockCalls());
            assertThat(calls(members, "get")).isEqualTo(expected.currentCalls());
            assertThat(calls(batches, "getAll")).isEqualTo(expected.batchCalls());
            assertThat(calls(reader, "read")).isEqualTo(expected.listCalls());
            assertThat(calls(reader, "unread")).isEqualTo(expected.unreadCalls());
            assertThat(calls(candidateClock, "instant")).isEqualTo(expected.clockCalls());
            assertThat(mockingDetails(client).getInvocations()).hasSize((int) (expected.currentCalls() + expected.batchCalls()));
            assertThat(mockingDetails(repository).getInvocations()).hasSize((int) (expected.listCalls() + expected.unreadCalls()));
            assertThat(mockingDetails(members).getInvocations()).hasSize((int) expected.currentCalls());
            assertThat(mockingDetails(reader).getInvocations()).hasSize((int) (expected.listCalls() + expected.unreadCalls()));
            assertThat(mockingDetails(batches).getInvocations()).hasSize((int) expected.batchCalls());
            if (expected.listCalls() == 1) {
                var sourceOrder = inOrder(client, sourceClock, repository);
                sourceOrder.verify(client).getContactMember(7L);
                sourceOrder.verify(sourceClock).instant();
                sourceOrder.verify(repository).notifications(7L, expected.cursor(), NOW);
                sourceOrder.verify(repository).unread(7L, NOW);
                var candidateOrder = inOrder(members, candidateClock, reader);
                candidateOrder.verify(members).get(7L);
                candidateOrder.verify(candidateClock).instant();
                candidateOrder.verify(reader).read(7L, expected.cursor(), NOW);
                candidateOrder.verify(reader).unread(7L, NOW);
            }
            cache(before);
            cache(after);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void givenActiveOrPendingSenderAndNullSender__whenBothMvcSerialize__thenKeepExactNullableSevenFieldsRawNicknameAndMicros(boolean active) throws Exception {
        long sender = Long.MAX_VALUE;
        UUID first = UUID.fromString(FIRST);
        UUID second = UUID.fromString(SECOND);
        Instant created = Instant.parse("2030-12-31T15:00:00.123456Z");
        Instant readAt = active ? null : created.plusSeconds(1);
        var client = mock(UserServiceClient.class);
        var repository = mock(PrivateNoteRepository.class);
        var sourceClock = mock(Clock.class);
        when(client.getContactMember(7L)).thenReturn(new PrivateNoteModels.ContactMember(7L, "synthetic", true, false));
        when(client.getContactMembers(List.of(sender))).thenReturn(List.of(
                new PrivateNoteModels.ContactMember(sender, active ? "  " : "미노출", active, !active)));
        when(sourceClock.instant()).thenReturn(NOW);
        when(repository.notifications(7L, null, NOW)).thenReturn(List.of(
                note(first, sender, created, readAt), note(second, null, created, null)));
        when(repository.unread(7L, NOW)).thenReturn(5L);
        var members = mock(ContactMemberQuery.class);
        var batches = mock(ContactMembersQuery.class);
        var reader = mock(NoteNotificationReader.class);
        var candidateClock = mock(Clock.class);
        when(members.get(7L)).thenReturn(new ContactMember(7L, "synthetic", true, false));
        when(batches.getAll(List.of(sender))).thenReturn(List.of(new ContactMember(sender, active ? "  " : "미노출", active, !active)));
        when(candidateClock.instant()).thenReturn(NOW);
        when(reader.read(7L, null, NOW)).thenReturn(List.of(
                new NoteNotificationReader.Row(first, sender, created, readAt),
                new NoteNotificationReader.Row(second, null, created, null)));
        when(reader.unread(7L, NOW)).thenReturn(5L);

        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            var before = request(original(new F21LegacyNotificationsService(repository, client, sourceClock), mapper), null);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L, null, List.of()));
            var after = request(candidate(new ReadNoteNotifications(members, reader, batches, candidateClock), mapper), null);
            assertThat(before.getResponse().getStatus()).isEqualTo(200);
            assertThat(after.getResponse().getStatus()).isEqualTo(200);
            var wire = mapper.readTree(before.getResponse().getContentAsByteArray());
            assertThat(mapper.readTree(after.getResponse().getContentAsByteArray())).isEqualTo(wire);
            String actorId = active ? Long.toString(sender) : "null";
            String nickname = active ? "  " : "탈퇴한 회원";
            assertThat(wire).isEqualTo(mapper.readTree("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"content\":["
                    + "{\"noteId\":\"" + first + "\",\"kind\":\"PRIVATE_NOTE\",\"actorId\":" + actorId + ",\"actorNickname\":\"" + nickname
                    + "\",\"createdAt\":\"2030-12-31T15:00:00.123456Z\",\"read\":" + !active + ",\"href\":\"/notes/" + first + "\"},"
                    + "{\"noteId\":\"" + second + "\",\"kind\":\"PRIVATE_NOTE\",\"actorId\":null,\"actorNickname\":\"탈퇴한 회원\","
                    + "\"createdAt\":\"2030-12-31T15:00:00.123456Z\",\"read\":false,\"href\":\"/notes/" + second + "\"}],\"nextCursor\":null,\"unreadCount\":5}}"));
            assertThat(wire.size()).isEqualTo(3);
            assertThat(wire.path("data").size()).isEqualTo(3);
            assertThat(wire.path("data").path("nextCursor").isNull()).isTrue();
            assertThat(wire.path("data").path("unreadCount").isIntegralNumber()).isTrue();
            for (var item : wire.path("data").path("content")) assertThat(item.size()).isEqualTo(7);
            if (active) {
                var actor = wire.path("data").path("content").get(0).path("actorId");
                assertThat(actor.isIntegralNumber()).isTrue();
                assertThat(actor.longValue()).isEqualTo(sender);
            }
            var sourceOrder = inOrder(client, sourceClock, repository);
            sourceOrder.verify(client).getContactMember(7L);
            sourceOrder.verify(sourceClock).instant();
            sourceOrder.verify(repository).notifications(7L, null, NOW);
            sourceOrder.verify(client).getContactMembers(List.of(sender));
            sourceOrder.verify(repository).unread(7L, NOW);
            var candidateOrder = inOrder(members, candidateClock, reader, batches);
            candidateOrder.verify(members).get(7L);
            candidateOrder.verify(candidateClock).instant();
            candidateOrder.verify(reader).read(7L, null, NOW);
            candidateOrder.verify(batches).getAll(List.of(sender));
            candidateOrder.verify(reader).unread(7L, NOW);
            verifyNoMoreInteractions(client, sourceClock, repository, members, candidateClock, reader, batches);
            cache(before);
            cache(after);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static PrivateNoteModels.Note note(UUID id, Long sender, Instant createdAt, Instant readAt) {
        return new PrivateNoteModels.Note(id, sender, 7L, "synthetic private body must stay absent", null, null, null,
                createdAt, NOW.plusSeconds(60), "INBOX", readAt, false);
    }

    private MockMvc original(F21LegacyNotificationsService service, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new F21LegacyNotificationsController(service))
                .setControllerAdvice(new F21LegacyNotificationsExceptionAdvice(), new GlobalExceptionRestAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new PrivateNoteHttpBoundary()).build();
    }

    private MockMvc candidate(ReadNoteNotifications service, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new NoteNotificationsController(service))
                .setControllerAdvice(new NoteNotificationsExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    private MvcResult request(MockMvc mvc, String[] values) throws Exception {
        var request = get("/api/v1/notes/notifications").header("X-User-Id", "7");
        if (values != null) request.param("cursor", values);
        return mvc.perform(request).andReturn();
    }

    private void cache(MvcResult result) {
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }

    private static long calls(Object mock, String method) {
        return mockingDetails(mock).getInvocations().stream().filter(call -> call.getMethod().getName().equals(method)).count();
    }

    private record MeasuredBinding(int status, boolean reached, UUID cursor, String body,
                                   long currentCalls, long listCalls, long batchCalls, long unreadCalls, long clockCalls) {
    }

    static Stream<Arguments> bindings() {
        // Measured from original-only annotated MVC; SOURCE d436e3c43fa220cd4004d9f39040f809915ec6ae.
        // Observation SHA-256: 9876373f6a4cf7b9adeae3624d27140bed5dff57e1e28a2acf8ebf195cff2eb2
        return Stream.of(Arguments.of("missing", (String[]) null, new MeasuredBinding(200, true, null, "{\"code\":200,\"message\":\"\uc694\uccad\uc774 \uc131\uacf5\uc801\uc73c\ub85c \ucc98\ub9ac\ub418\uc5c8\uc2b5\ub2c8\ub2e4.\",\"data\":{\"content\":[],\"nextCursor\":null,\"unreadCount\":3}}", 1L, 1L, 0L, 1L, 1L)),
                Arguments.of("empty", new String[]{""}, new MeasuredBinding(200, true, null, "{\"code\":200,\"message\":\"\uc694\uccad\uc774 \uc131\uacf5\uc801\uc73c\ub85c \ucc98\ub9ac\ub418\uc5c8\uc2b5\ub2c8\ub2e4.\",\"data\":{\"content\":[],\"nextCursor\":null,\"unreadCount\":3}}", 1L, 1L, 0L, 1L, 1L)),
                Arguments.of("space", new String[]{" "}, new MeasuredBinding(200, true, null, "{\"code\":200,\"message\":\"\uc694\uccad\uc774 \uc131\uacf5\uc801\uc73c\ub85c \ucc98\ub9ac\ub418\uc5c8\uc2b5\ub2c8\ub2e4.\",\"data\":{\"content\":[],\"nextCursor\":null,\"unreadCount\":3}}", 1L, 1L, 0L, 1L, 1L)),
                Arguments.of("canonical", new String[]{FIRST}, new MeasuredBinding(200, true, UUID.fromString("f2100000-0000-0000-0000-000000000001"), "{\"code\":200,\"message\":\"\uc694\uccad\uc774 \uc131\uacf5\uc801\uc73c\ub85c \ucc98\ub9ac\ub418\uc5c8\uc2b5\ub2c8\ub2e4.\",\"data\":{\"content\":[],\"nextCursor\":null,\"unreadCount\":3}}", 1L, 1L, 0L, 1L, 1L)),
                Arguments.of("uppercase", new String[]{FIRST.toUpperCase(java.util.Locale.ROOT)}, new MeasuredBinding(200, true, UUID.fromString("f2100000-0000-0000-0000-000000000001"), "{\"code\":200,\"message\":\"\uc694\uccad\uc774 \uc131\uacf5\uc801\uc73c\ub85c \ucc98\ub9ac\ub418\uc5c8\uc2b5\ub2c8\ub2e4.\",\"data\":{\"content\":[],\"nextCursor\":null,\"unreadCount\":3}}", 1L, 1L, 0L, 1L, 1L)),
                Arguments.of("shortened", new String[]{"1-1-1-1-1"}, new MeasuredBinding(200, true, UUID.fromString("00000001-0001-0001-0001-000000000001"), "{\"code\":200,\"message\":\"\uc694\uccad\uc774 \uc131\uacf5\uc801\uc73c\ub85c \ucc98\ub9ac\ub418\uc5c8\uc2b5\ub2c8\ub2e4.\",\"data\":{\"content\":[],\"nextCursor\":null,\"unreadCount\":3}}", 1L, 1L, 0L, 1L, 1L)),
                Arguments.of("repeated-same", new String[]{FIRST, FIRST}, new MeasuredBinding(200, true, UUID.fromString("f2100000-0000-0000-0000-000000000001"), "{\"code\":200,\"message\":\"\uc694\uccad\uc774 \uc131\uacf5\uc801\uc73c\ub85c \ucc98\ub9ac\ub418\uc5c8\uc2b5\ub2c8\ub2e4.\",\"data\":{\"content\":[],\"nextCursor\":null,\"unreadCount\":3}}", 1L, 1L, 0L, 1L, 1L)),
                Arguments.of("repeated-different", new String[]{FIRST, SECOND}, new MeasuredBinding(200, true, UUID.fromString("f2100000-0000-0000-0000-000000000001"), "{\"code\":200,\"message\":\"\uc694\uccad\uc774 \uc131\uacf5\uc801\uc73c\ub85c \ucc98\ub9ac\ub418\uc5c8\uc2b5\ub2c8\ub2e4.\",\"data\":{\"content\":[],\"nextCursor\":null,\"unreadCount\":3}}", 1L, 1L, 0L, 1L, 1L)),
                Arguments.of("empty-repeated", new String[]{"", FIRST}, new MeasuredBinding(200, true, null, "{\"code\":200,\"message\":\"\uc694\uccad\uc774 \uc131\uacf5\uc801\uc73c\ub85c \ucc98\ub9ac\ub418\uc5c8\uc2b5\ub2c8\ub2e4.\",\"data\":{\"content\":[],\"nextCursor\":null,\"unreadCount\":3}}", 1L, 1L, 0L, 1L, 1L)),
                Arguments.of("csv", new String[]{FIRST + "," + SECOND}, new MeasuredBinding(400, false, null, "{\"message\":\"\uc694\uccad \ud615\uc2dd\uacfc \ub85c\uadf8\uc778 \uc815\ubcf4\ub97c \ud655\uc778\ud574 \uc8fc\uc138\uc694.\"}", 0L, 0L, 0L, 0L, 0L)),
                Arguments.of("invalid", new String[]{"abc"}, new MeasuredBinding(400, false, null, "{\"message\":\"\uc694\uccad \ud615\uc2dd\uacfc \ub85c\uadf8\uc778 \uc815\ubcf4\ub97c \ud655\uc778\ud574 \uc8fc\uc138\uc694.\"}", 0L, 0L, 0L, 0L, 0L)),
                Arguments.of("overflow-component", new String[]{"100000000-0000-0000-0000-000000000000"}, new MeasuredBinding(400, false, null, "{\"message\":\"\uc694\uccad \ud615\uc2dd\uacfc \ub85c\uadf8\uc778 \uc815\ubcf4\ub97c \ud655\uc778\ud574 \uc8fc\uc138\uc694.\"}", 0L, 0L, 0L, 0L, 0L)));
    }
}
