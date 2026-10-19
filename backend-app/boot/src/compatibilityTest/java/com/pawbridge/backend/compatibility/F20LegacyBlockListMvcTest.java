package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.note.application.ListNoteBlocks;
import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader;
import com.pawbridge.backend.community.internal.note.web.NoteBlockListController;
import com.pawbridge.backend.community.internal.note.web.NoteBlockListExceptionHandler;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.F20LegacyBlockListController;
import com.pawbridge.communityservice.contact.F20LegacyBlockListExceptionAdvice;
import com.pawbridge.communityservice.contact.F20LegacyBlockListService;
import com.pawbridge.communityservice.contact.PrivateNoteHttpBoundary;
import com.pawbridge.communityservice.contact.PrivateNoteModels;
import com.pawbridge.communityservice.contact.PrivateNoteRepository;
import com.pawbridge.communityservice.exception.common.GlobalExceptionRestAdvice;
import java.time.Instant;
import java.util.List;
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

/** Selected original MVC versus candidate; binding expectations come from the original-only 12-case observation. */
class F20LegacyBlockListMvcTest {
    @ParameterizedTest
    @MethodSource("bindings")
    void givenMeasuredOriginalPageQueryStrings__whenBothMvcBind__thenBoundPageFailurePriorityCallsAndExactWireAgree(
            String label, String[] values, MeasuredBinding expected) throws Exception {
        assertThat(expected).as("Unmeasured F20 original binding '%s': fill from original-only observation before running this comparison", label)
                .isNotNull();
        var parameter = F20LegacyBlockListController.class.getMethod("blocks", long.class, int.class).getParameters()[1];
        assertThat(parameter.isNamePresent()).isTrue();
        assertThat(parameter.getName()).isEqualTo("page");
        var client = mock(UserServiceClient.class);
        var repository = mock(PrivateNoteRepository.class);
        when(client.getContactMember(7L)).thenReturn(new PrivateNoteModels.ContactMember(7L, "synthetic", true, false));
        when(client.getContactMembers(anyList())).thenReturn(List.of());
        when(repository.blockCount(7L)).thenReturn(0L);
        when(repository.blocks(eq(7L), anyInt())).thenReturn(List.of());
        var original = spy(new F20LegacyBlockListService(repository, client));
        var sourceReached = new AtomicBoolean();
        var sourceBound = new AtomicReference<Integer>();
        doAnswer(call -> {
            sourceReached.set(true);
            sourceBound.set(call.getArgument(1, Integer.class));
            return call.callRealMethod();
        }).when(original).blocks(eq(7L), anyInt());

        var members = mock(ContactMemberQuery.class);
        var blocks = mock(NoteBlockListReader.class);
        var batches = mock(ContactMembersQuery.class);
        when(members.get(7L)).thenReturn(new ContactMember(7L, "synthetic", true, false));
        when(blocks.count(7L)).thenReturn(0L);
        when(blocks.read(eq(7L), anyInt())).thenReturn(List.of());
        when(batches.getAll(anyList())).thenReturn(List.of());
        var candidate = spy(new ListNoteBlocks(members, blocks, batches));
        var candidateReached = new AtomicBoolean();
        var candidateBound = new AtomicReference<Integer>();
        doAnswer(call -> {
            candidateReached.set(true);
            candidateBound.set(call.getArgument(1, Integer.class));
            return call.callRealMethod();
        }).when(candidate).read(eq(7L), anyInt());

        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            var before = request(original(original, mapper), values);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L, null, List.of()));
            var after = request(candidate(candidate, mapper), values);
            assertThat(before.getResponse().getStatus()).as(label).isEqualTo(expected.status());
            assertThat(after.getResponse().getStatus()).as(label).isEqualTo(expected.status());
            JsonNode body = mapper.readTree(before.getResponse().getContentAsByteArray());
            assertThat(body).as(label).isEqualTo(mapper.readTree(expected.body()));
            assertThat(mapper.readTree(after.getResponse().getContentAsByteArray())).as(label).isEqualTo(body);
            assertThat(sourceReached.get()).isEqualTo(expected.reached());
            assertThat(candidateReached.get()).isEqualTo(expected.reached());
            assertThat(sourceBound.get()).isEqualTo(expected.page());
            assertThat(candidateBound.get()).isEqualTo(expected.page());
            assertThat(calls(client, "getContactMember")).isEqualTo(expected.currentCalls());
            assertThat(calls(client, "getContactMembers")).isEqualTo(expected.batchCalls());
            assertThat(calls(repository, "blockCount")).isEqualTo(expected.countCalls());
            assertThat(calls(repository, "blocks")).isEqualTo(expected.listCalls());
            assertThat(calls(members, "get")).isEqualTo(expected.currentCalls());
            assertThat(calls(batches, "getAll")).isEqualTo(expected.batchCalls());
            assertThat(calls(blocks, "count")).isEqualTo(expected.countCalls());
            assertThat(calls(blocks, "read")).isEqualTo(expected.listCalls());
            assertThat(mockingDetails(client).getInvocations()).hasSize((int) (expected.currentCalls() + expected.batchCalls()));
            assertThat(mockingDetails(repository).getInvocations()).hasSize((int) (expected.countCalls() + expected.listCalls()));
            if (expected.listCalls() == 1) {
                var sourceOrder = inOrder(client, repository);
                sourceOrder.verify(client).getContactMember(7L);
                sourceOrder.verify(repository).blockCount(7L);
                sourceOrder.verify(repository).blocks(7L, expected.page());
                var candidateOrder = inOrder(members, blocks);
                candidateOrder.verify(members).get(7L);
                candidateOrder.verify(blocks).count(7L);
                candidateOrder.verify(blocks).read(7L, expected.page());
            }
            cache(before);
            cache(after);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void givenActiveOrInactiveTarget__whenBothMvcSerialize__thenKeepRawLongIdInstantExactPageShapeAndOriginalDisplay(boolean active) throws Exception {
        long id = Long.MAX_VALUE;
        Instant createdAt = Instant.parse("2031-01-01T00:00:00.123456Z");
        String nickname = active ? "  " : "탈퇴한 회원";
        var client = mock(UserServiceClient.class);
        var repository = mock(PrivateNoteRepository.class);
        when(client.getContactMember(7L)).thenReturn(new PrivateNoteModels.ContactMember(7L, "synthetic", true, false));
        when(client.getContactMembers(List.of(id))).thenReturn(List.of(
                new PrivateNoteModels.ContactMember(id, active ? "  " : "원문을 노출하지 않음", active, false)));
        when(repository.blockCount(7L)).thenReturn(1L);
        when(repository.blocks(7L, 0)).thenReturn(List.of(new PrivateNoteRepository.Block(id, createdAt)));
        var members = mock(ContactMemberQuery.class);
        var blocks = mock(NoteBlockListReader.class);
        var batches = mock(ContactMembersQuery.class);
        when(members.get(7L)).thenReturn(new ContactMember(7L, "synthetic", true, false));
        when(blocks.count(7L)).thenReturn(1L);
        when(blocks.read(7L, 0)).thenReturn(List.of(new NoteBlockListReader.Block(id, createdAt)));
        when(batches.getAll(List.of(id))).thenReturn(List.of(new ContactMember(id, active ? "  " : "원문을 노출하지 않음", active, false)));

        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            var before = request(original(new F20LegacyBlockListService(repository, client), mapper), new String[]{"0"});
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L, null, List.of()));
            var after = request(candidate(new ListNoteBlocks(members, blocks, batches), mapper), new String[]{"0"});
            assertThat(before.getResponse().getStatus()).isEqualTo(200);
            assertThat(after.getResponse().getStatus()).isEqualTo(200);
            JsonNode body = mapper.readTree(before.getResponse().getContentAsByteArray());
            assertThat(mapper.readTree(after.getResponse().getContentAsByteArray())).isEqualTo(body);
            assertThat(body).isEqualTo(mapper.readTree("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{"
                    + "\"content\":[{\"memberId\":" + id + ",\"nickname\":\"" + nickname + "\",\"createdAt\":\"2031-01-01T00:00:00.123456Z\"}],"
                    + "\"totalElements\":1,\"totalPages\":1,\"number\":0}}"));
            assertThat(body.size()).isEqualTo(3);
            assertThat(body.path("data").size()).isEqualTo(4);
            assertThat(body.path("data").path("content").get(0).size()).isEqualTo(3);
            assertThat(body.path("data").path("content").get(0).path("memberId").isIntegralNumber()).isTrue();
            assertThat(body.path("data").path("content").get(0).path("memberId").longValue()).isEqualTo(id);
            var sourceOrder = inOrder(client, repository);
            sourceOrder.verify(client).getContactMember(7L);
            sourceOrder.verify(repository).blockCount(7L);
            sourceOrder.verify(repository).blocks(7L, 0);
            sourceOrder.verify(client).getContactMembers(List.of(id));
            var candidateOrder = inOrder(members, blocks, batches);
            candidateOrder.verify(members).get(7L);
            candidateOrder.verify(blocks).count(7L);
            candidateOrder.verify(blocks).read(7L, 0);
            candidateOrder.verify(batches).getAll(List.of(id));
            verifyNoMoreInteractions(client, repository, members, blocks, batches);
            cache(before);
            cache(after);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private MockMvc original(F20LegacyBlockListService service, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new F20LegacyBlockListController(service))
                .setControllerAdvice(new F20LegacyBlockListExceptionAdvice(), new GlobalExceptionRestAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new PrivateNoteHttpBoundary()).build();
    }

    private MockMvc candidate(ListNoteBlocks service, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new NoteBlockListController(service))
                .setControllerAdvice(new NoteBlockListExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    private MvcResult request(MockMvc mvc, String[] values) throws Exception {
        var request = get("/api/v1/notes/blocks").header("X-User-Id", "7");
        if (values != null) request.param("page", values);
        return mvc.perform(request).andReturn();
    }

    private void cache(MvcResult result) {
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }

    private static long calls(Object mock, String method) {
        return mockingDetails(mock).getInvocations().stream().filter(call -> call.getMethod().getName().equals(method)).count();
    }

    private record MeasuredBinding(int status, boolean reached, Integer page, String body,
                                   long currentCalls, long countCalls, long listCalls, long batchCalls) {
    }

    static Stream<Arguments> bindings() {
        // Measured original-only binding cases; source and compiled parameter metadata were verified first.
        // Observation SHA-256: 4cd9c262b8dd29f1c0a6288a551c6441e622e7b27d02a6eacb91faeb586e12c2
        return Stream.of(Arguments.of("missing", (String[]) null, new MeasuredBinding(200, true, 0, "{\"code\":200,\"message\":\"\uc694\uccad\uc774 \uc131\uacf5\uc801\uc73c\ub85c \ucc98\ub9ac\ub418\uc5c8\uc2b5\ub2c8\ub2e4.\",\"data\":{\"content\":[],\"totalElements\":0,\"totalPages\":0,\"number\":0}}", 1, 1, 1, 0)),
                Arguments.of("empty", new String[]{""}, new MeasuredBinding(200, true, 0, "{\"code\":200,\"message\":\"\uc694\uccad\uc774 \uc131\uacf5\uc801\uc73c\ub85c \ucc98\ub9ac\ub418\uc5c8\uc2b5\ub2c8\ub2e4.\",\"data\":{\"content\":[],\"totalElements\":0,\"totalPages\":0,\"number\":0}}", 1, 1, 1, 0)),
                Arguments.of("space", new String[]{" "}, new MeasuredBinding(400, false, null, "{\"message\":\"\uc694\uccad \ud615\uc2dd\uacfc \ub85c\uadf8\uc778 \uc815\ubcf4\ub97c \ud655\uc778\ud574 \uc8fc\uc138\uc694.\"}", 0, 0, 0, 0)),
                Arguments.of("normal", new String[]{"2"}, new MeasuredBinding(200, true, 2, "{\"code\":200,\"message\":\"\uc694\uccad\uc774 \uc131\uacf5\uc801\uc73c\ub85c \ucc98\ub9ac\ub418\uc5c8\uc2b5\ub2c8\ub2e4.\",\"data\":{\"content\":[],\"totalElements\":0,\"totalPages\":0,\"number\":2}}", 1, 1, 1, 0)),
                Arguments.of("negative", new String[]{"-1"}, new MeasuredBinding(400, true, -1, "{\"message\":\"\ud398\uc774\uc9c0 \ubc94\uc704\ub97c \ud655\uc778\ud574 \uc8fc\uc138\uc694.\"}", 0, 0, 0, 0)),
                Arguments.of("above-ceiling", new String[]{"10001"}, new MeasuredBinding(400, true, 10001, "{\"message\":\"\ud398\uc774\uc9c0 \ubc94\uc704\ub97c \ud655\uc778\ud574 \uc8fc\uc138\uc694.\"}", 0, 0, 0, 0)),
                Arguments.of("ceiling", new String[]{"10000"}, new MeasuredBinding(200, true, 10000, "{\"code\":200,\"message\":\"\uc694\uccad\uc774 \uc131\uacf5\uc801\uc73c\ub85c \ucc98\ub9ac\ub418\uc5c8\uc2b5\ub2c8\ub2e4.\",\"data\":{\"content\":[],\"totalElements\":0,\"totalPages\":0,\"number\":10000}}", 1, 1, 1, 0)),
                Arguments.of("repeated", new String[]{"2", "3"}, new MeasuredBinding(200, true, 2, "{\"code\":200,\"message\":\"\uc694\uccad\uc774 \uc131\uacf5\uc801\uc73c\ub85c \ucc98\ub9ac\ub418\uc5c8\uc2b5\ub2c8\ub2e4.\",\"data\":{\"content\":[],\"totalElements\":0,\"totalPages\":0,\"number\":2}}", 1, 1, 1, 0)),
                Arguments.of("empty-repeated", new String[]{"", "2"}, new MeasuredBinding(400, false, null, "{\"message\":\"\uc694\uccad \ud615\uc2dd\uacfc \ub85c\uadf8\uc778 \uc815\ubcf4\ub97c \ud655\uc778\ud574 \uc8fc\uc138\uc694.\"}", 0, 0, 0, 0)),
                Arguments.of("csv", new String[]{"2,3"}, new MeasuredBinding(400, false, null, "{\"message\":\"\uc694\uccad \ud615\uc2dd\uacfc \ub85c\uadf8\uc778 \uc815\ubcf4\ub97c \ud655\uc778\ud574 \uc8fc\uc138\uc694.\"}", 0, 0, 0, 0)),
                Arguments.of("invalid", new String[]{"abc"}, new MeasuredBinding(400, false, null, "{\"message\":\"\uc694\uccad \ud615\uc2dd\uacfc \ub85c\uadf8\uc778 \uc815\ubcf4\ub97c \ud655\uc778\ud574 \uc8fc\uc138\uc694.\"}", 0, 0, 0, 0)),
                Arguments.of("overflow", new String[]{"2147483648"}, new MeasuredBinding(400, false, null, "{\"message\":\"\uc694\uccad \ud615\uc2dd\uacfc \ub85c\uadf8\uc778 \uc815\ubcf4\ub97c \ud655\uc778\ud574 \uc8fc\uc138\uc694.\"}", 0, 0, 0, 0)));
    }
}
