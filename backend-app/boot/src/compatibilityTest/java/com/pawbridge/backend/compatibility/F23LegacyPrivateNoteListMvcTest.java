package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.note.application.ListPrivateNotes;
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader;
import com.pawbridge.backend.community.internal.note.application.PrivateNoteListReader;
import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteListController;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteListExceptionHandler;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.F23LegacyPrivateNoteListController;
import com.pawbridge.communityservice.contact.F23LegacyPrivateNoteListExceptionAdvice;
import com.pawbridge.communityservice.contact.F23LegacyPrivateNoteListService;
import com.pawbridge.communityservice.contact.PrivateNoteHttpBoundary;
import com.pawbridge.communityservice.contact.PrivateNoteModels;
import com.pawbridge.communityservice.contact.PrivateNoteRepository;
import com.pawbridge.communityservice.exception.common.GlobalExceptionRestAdvice;
import feign.FeignException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RequestParam;

/** Prepared only: fill all query expectations from a complete original-only observation before comparison. */
class F23LegacyPrivateNoteListMvcTest {
    private static final Instant NOW = Instant.parse("2031-01-01T00:00:00.123456Z");
    private static final Instant CREATED = Instant.parse("2030-12-31T15:00:00.123456Z");

    @ParameterizedTest
    @MethodSource("queries")
    void givenMeasuredOriginalListQuery__whenBothMvcBind__thenWireArgumentsAndFailurePhasesAgree(
            String label, String[][] query, MeasuredList expected) throws Exception {
        assertThat(expected).as("UNMEASURED F23 original list query '%s': fill only after original-only observation", label)
                .isNotNull();
        var parameters = F23LegacyPrivateNoteListController.class
                .getMethod("list", long.class, String.class, boolean.class, int.class).getParameters();
        assertThat(parameters[1].getAnnotation(RequestParam.class).name()).isEqualTo("box");
        assertThat(parameters[1].getAnnotation(RequestParam.class).defaultValue()).isEqualTo("INBOX");
        for (int index : new int[]{2, 3}) {
            var parameter = parameters[index];
            var annotation = parameter.getAnnotation(RequestParam.class);
            assertThat(parameter.isNamePresent()).isTrue();
            assertThat(parameter.getName()).isEqualTo(index == 2 ? "favorites" : "page");
            assertThat(annotation.name()).isEmpty();
            assertThat(annotation.value()).isEmpty();
            assertThat(annotation.defaultValue()).isEqualTo(index == 2 ? "false" : "0");
        }
        var fixture = new Fixture();
        var source = spy(fixture.original);
        var sourceBound = new AtomicReference<BoundList>();
        doAnswer(call -> {
            sourceBound.set(new BoundList(call.getArgument(1, String.class), call.getArgument(2, Boolean.class),
                    call.getArgument(3, Integer.class)));
            return call.callRealMethod();
        }).when(source).list(eq(7L), nullable(String.class), anyBoolean(), anyInt());
        var candidate = spy(fixture.candidate);
        var candidateBound = new AtomicReference<BoundList>();
        doAnswer(call -> {
            candidateBound.set(new BoundList(call.getArgument(1, String.class), call.getArgument(2, Boolean.class),
                    call.getArgument(3, Integer.class)));
            return call.callRealMethod();
        }).when(candidate).read(eq(7L), nullable(String.class), anyBoolean(), anyInt());

        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            var before = request(original(source, mapper), query);
            authenticate();
            var after = request(candidate(candidate, mapper), query);
            assertThat(before.getResponse().getStatus()).as(label).isEqualTo(expected.status());
            assertThat(after.getResponse().getStatus()).as(label).isEqualTo(expected.status());
            var wire = mapper.readTree(before.getResponse().getContentAsByteArray());
            assertThat(wire).isEqualTo(mapper.readTree(expected.body()));
            assertThat(mapper.readTree(after.getResponse().getContentAsByteArray())).isEqualTo(wire);
            assertThat(sourceBound.get() != null).isEqualTo(expected.reached());
            assertThat(candidateBound.get() != null).isEqualTo(expected.reached());
            BoundList bound = expected.reached() ? new BoundList(expected.box(), expected.favorites(), expected.page()) : null;
            assertThat(sourceBound.get()).isEqualTo(bound);
            assertThat(candidateBound.get()).isEqualTo(bound);
            assertThat(fixture.sourcePhases).containsExactlyElementsOf(expected.phases());
            assertThat(fixture.candidatePhases).containsExactlyElementsOf(expected.phases());
            assertThat(calls(fixture.client, "getContactMember")).isEqualTo(expected.callerCalls());
            assertThat(calls(fixture.members, "get")).isEqualTo(expected.callerCalls());
            assertThat(calls(fixture.repository, "count")).isEqualTo(expected.countCalls());
            assertThat(calls(fixture.reader, "count")).isEqualTo(expected.countCalls());
            assertThat(calls(fixture.repository, "list")).isEqualTo(expected.listCalls());
            assertThat(calls(fixture.reader, "read")).isEqualTo(expected.listCalls());
            assertThat(calls(fixture.client, "getContactMembers")).isEqualTo(expected.batchCalls());
            assertThat(calls(fixture.batches, "getAll")).isEqualTo(expected.batchCalls());
            assertThat(calls(fixture.repository, "blocked")).isEqualTo(expected.blockCalls());
            assertThat(calls(fixture.blocks, "blocked")).isEqualTo(expected.blockCalls());
            assertThat(calls(fixture.sourceClock, "instant")).isEqualTo(expected.clockCalls());
            assertThat(calls(fixture.candidateClock, "instant")).isEqualTo(expected.clockCalls());
            assertThat(mockingDetails(fixture.client).getInvocations()).hasSize((int) (expected.callerCalls() + expected.batchCalls()));
            assertThat(mockingDetails(fixture.repository).getInvocations()).hasSize((int) (expected.countCalls() + expected.listCalls() + expected.blockCalls()));
            assertThat(mockingDetails(fixture.members).getInvocations()).hasSize((int) expected.callerCalls());
            assertThat(mockingDetails(fixture.reader).getInvocations()).hasSize((int) (expected.countCalls() + expected.listCalls()));
            assertThat(mockingDetails(fixture.batches).getInvocations()).hasSize((int) expected.batchCalls());
            assertThat(mockingDetails(fixture.blocks).getInvocations()).hasSize((int) expected.blockCalls());
            cache(before);
            cache(after);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenInboxOrFavoriteSentPageWithRepeatedNullAndPendingCounterparts__whenBothMvcSerialize__thenKeepExactFivePlusTenRawFieldsAndOriginalPerRowBlockCalls(boolean sent) throws Exception {
        var fixture = new Fixture();
        String box = sent ? "SENT" : "INBOX";
        List<StoredNote> rows = rows(sent);
        List<Long> ids = List.of(Long.MAX_VALUE, 9L);
        doReturn(14L).when(fixture.repository).count(7L, box, sent, NOW);
        doReturn(originalRows(rows)).when(fixture.repository).list(7L, box, sent, 1, NOW);
        doReturn(List.of(new PrivateNoteModels.ContactMember(Long.MAX_VALUE, "  ", true, false),
                new PrivateNoteModels.ContactMember(9L, "탈퇴한 회원", false, true))).when(fixture.client).getContactMembers(ids);
        doReturn(false, true).when(fixture.repository).blocked(7L, Long.MAX_VALUE);
        doReturn(14L).when(fixture.reader).count(7L, box, sent, NOW);
        doReturn(rows).when(fixture.reader).read(7L, box, sent, 1, NOW);
        doReturn(List.of(new ContactMember(Long.MAX_VALUE, "  ", true, false),
                new ContactMember(9L, "탈퇴한 회원", false, true))).when(fixture.batches).getAll(ids);
        doReturn(false, true).when(fixture.blocks).blocked(7L, Long.MAX_VALUE);
        String[][] query = {{"box", box}, {"favorites", Boolean.toString(sent)}, {"page", "1"}};

        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            var before = request(original(fixture.original, mapper), query);
            authenticate();
            var after = request(candidate(fixture.candidate, mapper), query);
            assertThat(before.getResponse().getStatus()).isEqualTo(200);
            assertThat(after.getResponse().getStatus()).isEqualTo(200);
            var expected = mapper.createObjectNode().put("code", 200).put("message", "요청이 성공적으로 처리되었습니다.");
            var page = expected.putObject("data");
            page.put("totalElements", 14).put("totalPages", 2).put("number", 1).put("size", 10);
            var content = page.putArray("content");
            int[] serials = {3, 1, 4, 2};
            for (int index = 0; index < serials.length; index++) {
                var item = content.addObject().put("noteId", noteId(serials[index]).toString())
                        .put("body", body(index)).put("direction", box)
                        .put("createdAt", "2030-12-31T15:00:00.123456Z")
                        .put("favorite", sent || index % 2 == 0)
                        .put("counterpartNickname", index < 2 ? "  " : "탈퇴한 회원")
                        .put("canReply", !sent && index == 0);
                if (index < 2) item.put("counterpartId", Long.MAX_VALUE); else item.putNull("counterpartId");
                if (index == 1) item.put("readAt", "2030-12-31T15:00:00.123457Z");
                else if (index == 3) item.put("readAt", "2030-12-31T15:00:01.123456Z");
                else item.putNull("readAt");
                if (index == 0) item.put("contextHref", "/community/" + Long.MAX_VALUE);
                else if (index == 2) item.put("contextHref", "/reports/23");
                else item.putNull("contextHref");
            }
            var wire = mapper.readTree(before.getResponse().getContentAsByteArray());
            assertThat(wire).isEqualTo(expected);
            assertThat(mapper.readTree(after.getResponse().getContentAsByteArray())).isEqualTo(wire);
            assertThat(wire.size()).isEqualTo(3);
            assertThat(wire.path("data").size()).isEqualTo(5);
            assertThat(wire.path("data").path("content").size()).isEqualTo(4);
            for (var item : wire.path("data").path("content")) assertThat(item.size()).isEqualTo(10);
            assertThat(wire.path("data").path("content").get(0).path("counterpartId").isIntegralNumber()).isTrue();
            assertThat(wire.path("data").path("content").get(0).path("counterpartId").longValue()).isEqualTo(Long.MAX_VALUE);
            var sourceOrder = inOrder(fixture.client, fixture.sourceClock, fixture.repository);
            sourceOrder.verify(fixture.client).getContactMember(7L);
            sourceOrder.verify(fixture.sourceClock).instant();
            sourceOrder.verify(fixture.repository).count(7L, box, sent, NOW);
            sourceOrder.verify(fixture.repository).list(7L, box, sent, 1, NOW);
            sourceOrder.verify(fixture.client).getContactMembers(ids);
            if (!sent) sourceOrder.verify(fixture.repository, times(2)).blocked(7L, Long.MAX_VALUE);
            var candidateOrder = inOrder(fixture.members, fixture.candidateClock, fixture.reader, fixture.batches, fixture.blocks);
            candidateOrder.verify(fixture.members).get(7L);
            candidateOrder.verify(fixture.candidateClock).instant();
            candidateOrder.verify(fixture.reader).count(7L, box, sent, NOW);
            candidateOrder.verify(fixture.reader).read(7L, box, sent, 1, NOW);
            candidateOrder.verify(fixture.batches).getAll(ids);
            if (!sent) candidateOrder.verify(fixture.blocks, times(2)).blocked(7L, Long.MAX_VALUE);
            verifyNoMoreInteractions(fixture.client, fixture.sourceClock, fixture.repository, fixture.members,
                    fixture.candidateClock, fixture.reader, fixture.batches, fixture.blocks);
            cache(before);
            cache(after);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"count", "batch", "block", "clock"})
    void givenCountBatchBlockOrClockFailure__whenBothMvcHandle__thenKeepSafeSourceSpecific503AndStopWithoutPartialPage(String phase) throws Exception {
        var fixture = new Fixture();
        List<StoredNote> rows = rows(false).subList(0, 2);
        doReturn(2L).when(fixture.repository).count(7L, "INBOX", false, NOW);
        doReturn(originalRows(rows)).when(fixture.repository).list(7L, "INBOX", false, 0, NOW);
        doReturn(List.of(new PrivateNoteModels.ContactMember(Long.MAX_VALUE, "  ", true, false)))
                .when(fixture.client).getContactMembers(List.of(Long.MAX_VALUE));
        doReturn(2L).when(fixture.reader).count(7L, "INBOX", false, NOW);
        doReturn(rows).when(fixture.reader).read(7L, "INBOX", false, 0, NOW);
        doReturn(List.of(new ContactMember(Long.MAX_VALUE, "  ", true, false)))
                .when(fixture.batches).getAll(List.of(Long.MAX_VALUE));
        switch (phase) {
            case "count" -> {
                doThrow(new DataRetrievalFailureException("synthetic")).when(fixture.repository).count(7L, "INBOX", false, NOW);
                doThrow(new DataRetrievalFailureException("synthetic")).when(fixture.reader).count(7L, "INBOX", false, NOW);
            }
            case "batch" -> {
                var unavailable = mock(FeignException.class);
                when(unavailable.status()).thenReturn(500);
                doThrow(unavailable).when(fixture.client).getContactMembers(List.of(Long.MAX_VALUE));
                doThrow(new ContactMemberUnavailableException(new IllegalStateException("synthetic")))
                        .when(fixture.batches).getAll(List.of(Long.MAX_VALUE));
            }
            case "block" -> {
                doThrow(new DataRetrievalFailureException("synthetic")).when(fixture.repository).blocked(7L, Long.MAX_VALUE);
                doThrow(new DataRetrievalFailureException("synthetic")).when(fixture.blocks).blocked(7L, Long.MAX_VALUE);
            }
            case "clock" -> {
                doThrow(new IllegalStateException("synthetic")).when(fixture.sourceClock).instant();
                doThrow(new IllegalStateException("synthetic")).when(fixture.candidateClock).instant();
            }
            default -> throw new AssertionError("Unknown prepared phase");
        }
        String message = phase.equals("batch") ? "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
                : phase.equals("clock") ? "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."
                : "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.";
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            var before = request(original(fixture.original, mapper), new String[0][]);
            authenticate();
            var after = request(candidate(fixture.candidate, mapper), new String[0][]);
            var expected = mapper.createObjectNode().put("message", message);
            assertThat(before.getResponse().getStatus()).isEqualTo(503);
            assertThat(after.getResponse().getStatus()).isEqualTo(503);
            assertThat(mapper.readTree(before.getResponse().getContentAsByteArray())).isEqualTo(expected);
            assertThat(mapper.readTree(after.getResponse().getContentAsByteArray())).isEqualTo(expected);
            var sourceOrder = inOrder(fixture.client, fixture.sourceClock, fixture.repository);
            sourceOrder.verify(fixture.client).getContactMember(7L);
            sourceOrder.verify(fixture.sourceClock).instant();
            var candidateOrder = inOrder(fixture.members, fixture.candidateClock, fixture.reader, fixture.batches, fixture.blocks);
            candidateOrder.verify(fixture.members).get(7L);
            candidateOrder.verify(fixture.candidateClock).instant();
            if (!phase.equals("clock")) {
                sourceOrder.verify(fixture.repository).count(7L, "INBOX", false, NOW);
                candidateOrder.verify(fixture.reader).count(7L, "INBOX", false, NOW);
            }
            if (phase.equals("batch") || phase.equals("block")) {
                sourceOrder.verify(fixture.repository).list(7L, "INBOX", false, 0, NOW);
                sourceOrder.verify(fixture.client).getContactMembers(List.of(Long.MAX_VALUE));
                candidateOrder.verify(fixture.reader).read(7L, "INBOX", false, 0, NOW);
                candidateOrder.verify(fixture.batches).getAll(List.of(Long.MAX_VALUE));
            }
            if (phase.equals("block")) {
                sourceOrder.verify(fixture.repository).blocked(7L, Long.MAX_VALUE);
                candidateOrder.verify(fixture.blocks).blocked(7L, Long.MAX_VALUE);
            }
            verifyNoMoreInteractions(fixture.client, fixture.sourceClock, fixture.repository, fixture.members,
                    fixture.candidateClock, fixture.reader, fixture.batches, fixture.blocks);
            cache(before);
            cache(after);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static List<StoredNote> rows(boolean sent) {
        List<StoredNote> found = new ArrayList<>();
        int[] serials = {3, 1, 4, 2};
        Long[] counterparts = {Long.MAX_VALUE, Long.MAX_VALUE, null, 9L};
        Long[] contextIds = {Long.MAX_VALUE, null, 23L, null};
        for (int index = 0; index < serials.length; index++) {
            Long counterpart = counterparts[index];
            Long sender = sent ? Long.valueOf(7L) : counterpart;
            Long recipient = sent ? counterpart : Long.valueOf(7L);
            String context = index == 0 ? "POST" : index == 2 ? "REPORT" : null;
            Long contextId = contextIds[index];
            Instant readAt = index == 1 ? CREATED.plusNanos(1000) : index == 3 ? CREATED.plusSeconds(1) : null;
            found.add(new StoredNote(noteId(serials[index]), sender, recipient, body(index), context, contextId,
                    CREATED, sent ? "SENT" : "INBOX", readAt, sent || index % 2 == 0));
        }
        return List.copyOf(found);
    }

    private static List<PrivateNoteModels.Note> originalRows(List<StoredNote> rows) {
        return rows.stream().map(row -> new PrivateNoteModels.Note(row.noteId(), row.senderId(), row.recipientId(),
                row.body(), null, row.contextType(), row.contextId(), row.createdAt(), NOW.plusSeconds(60),
                row.direction(), row.readAt(), row.favorite())).toList();
    }

    private static UUID noteId(int serial) { return new UUID(23L, serial); }
    private static String body(int index) { return "  F23 synthetic body " + index + "\n<raw>  "; }

    private MockMvc original(F23LegacyPrivateNoteListService service, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new F23LegacyPrivateNoteListController(service))
                .setControllerAdvice(new F23LegacyPrivateNoteListExceptionAdvice(), new GlobalExceptionRestAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new PrivateNoteHttpBoundary()).build();
    }

    private MockMvc candidate(ListPrivateNotes service, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new PrivateNoteListController(service))
                .setControllerAdvice(new PrivateNoteListExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    private MvcResult request(MockMvc mvc, String[][] query) throws Exception {
        var request = get("/api/v1/notes").header("X-User-Id", "7");
        for (String[] entry : query) request.param(entry[0], Arrays.copyOfRange(entry, 1, entry.length));
        return mvc.perform(request).andReturn();
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

    private static final class Fixture {
        private final UserServiceClient client = mock(UserServiceClient.class);
        private final PrivateNoteRepository repository = mock(PrivateNoteRepository.class);
        private final Clock sourceClock = mock(Clock.class);
        private final ContactMemberQuery members = mock(ContactMemberQuery.class);
        private final PrivateNoteListReader reader = mock(PrivateNoteListReader.class);
        private final ContactMembersQuery batches = mock(ContactMembersQuery.class);
        private final NoteBlockReader blocks = mock(NoteBlockReader.class);
        private final Clock candidateClock = mock(Clock.class);
        private final List<String> sourcePhases = new ArrayList<>(), candidatePhases = new ArrayList<>();
        private final F23LegacyPrivateNoteListService original;
        private final ListPrivateNotes candidate;

        private Fixture() {
            when(client.getContactMember(7L)).thenAnswer(call -> {
                sourcePhases.add("caller"); return new PrivateNoteModels.ContactMember(7L, "synthetic", true, false);
            });
            when(sourceClock.instant()).thenAnswer(call -> { sourcePhases.add("clock"); return NOW; });
            when(repository.count(eq(7L), nullable(String.class), anyBoolean(), eq(NOW)))
                    .thenAnswer(call -> { sourcePhases.add("count"); return 0L; });
            when(repository.list(eq(7L), nullable(String.class), anyBoolean(), anyInt(), eq(NOW)))
                    .thenAnswer(call -> { sourcePhases.add("list"); return List.of(); });
            when(members.get(7L)).thenAnswer(call -> {
                candidatePhases.add("caller"); return new ContactMember(7L, "synthetic", true, false);
            });
            when(candidateClock.instant()).thenAnswer(call -> { candidatePhases.add("clock"); return NOW; });
            when(reader.count(eq(7L), nullable(String.class), anyBoolean(), eq(NOW)))
                    .thenAnswer(call -> { candidatePhases.add("count"); return 0L; });
            when(reader.read(eq(7L), nullable(String.class), anyBoolean(), anyInt(), eq(NOW)))
                    .thenAnswer(call -> { candidatePhases.add("list"); return List.of(); });
            original = new F23LegacyPrivateNoteListService(repository, client, sourceClock);
            candidate = new ListPrivateNotes(members, reader, batches, blocks, candidateClock);
        }
    }

    private record BoundList(String box, boolean favorites, int page) { }
    private record MeasuredList(int status, boolean reached, String box, Boolean favorites, Integer page, String body,
                                long callerCalls, long countCalls, long listCalls, long batchCalls, long blockCalls,
                                long clockCalls, List<String> phases) { }

    static Stream<Arguments> queries() {
        // Filled only from original-only JSON SHA256 7f69af07c0a5afb0b8aafb9830d376e30c88b1f937ad0c49cdd31ab511b1be4a.
        return Stream.of(Arguments.of("defaults", new String[0][], new MeasuredList(200, true, "INBOX", false, 0, "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"content\":[],\"totalElements\":0,\"totalPages\":0,\"number\":0,\"size\":10}}", 1L, 1L, 1L, 0L, 0L, 1L, List.of("caller", "clock", "count", "list"))),
                Arguments.of("sent-favorite-page", new String[][]{{"box", "SENT"}, {"favorites", "true"}, {"page", "1"}}, new MeasuredList(200, true, "SENT", true, 1, "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"content\":[],\"totalElements\":0,\"totalPages\":0,\"number\":1,\"size\":10}}", 1L, 1L, 1L, 0L, 0L, 1L, List.of("caller", "clock", "count", "list"))),
                Arguments.of("invalid-page-before-box", new String[][]{{"box", "bad"}, {"page", "-1"}}, new MeasuredList(400, true, "bad", false, -1, "{\"message\":\"페이지 범위를 확인해 주세요.\"}", 0L, 0L, 0L, 0L, 0L, 0L, List.of())),
                Arguments.of("invalid-box", new String[][]{{"box", "inbox"}}, new MeasuredList(400, true, "inbox", false, 0, "{\"message\":\"쪽지함을 확인해 주세요.\"}", 0L, 0L, 0L, 0L, 0L, 0L, List.of())),
                Arguments.of("invalid-favorites", new String[][]{{"favorites", "not-a-boolean"}}, new MeasuredList(400, false, null, null, null, "{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", 0L, 0L, 0L, 0L, 0L, 0L, List.of())),
                Arguments.of("conversion-before-page-box", new String[][]{{"box", "bad"}, {"favorites", "not-a-boolean"}, {"page", "-1"}}, new MeasuredList(400, false, null, null, null, "{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", 0L, 0L, 0L, 0L, 0L, 0L, List.of())));
    }
}
