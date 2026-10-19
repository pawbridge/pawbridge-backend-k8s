package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader;
import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors;
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusController;
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusExceptionHandler;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.F18LegacyBlockedAuthorsController;
import com.pawbridge.communityservice.contact.F18LegacyBlockedAuthorsService;
import com.pawbridge.communityservice.contact.PrivateNoteExceptionAdvice;
import com.pawbridge.communityservice.contact.PrivateNoteHttpBoundary;
import com.pawbridge.communityservice.contact.PrivateNoteModels;
import com.pawbridge.communityservice.contact.PrivateNoteRepository;
import com.pawbridge.communityservice.exception.common.GlobalExceptionRestAdvice;
import java.util.ArrayList;
import java.util.Arrays;
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

/** Selected actual original MVC/advice/boundary versus candidate; no JWT transport or database is started. */
class F18LegacyBlockedAuthorsMvcTest {
    @ParameterizedTest @MethodSource("bindings")
    void givenMeasuredOriginalQueryStrings__whenBothMvcBind__thenRawListsFailurePriorityAndExactMixedBodiesAgree(
            String label, String[] parameters, boolean reached, List<Long> raw, int status, String bodyKind) throws Exception {
        var parameter = F18LegacyBlockedAuthorsController.class.getMethod("blockedAuthors", long.class, List.class).getParameters()[1];
        assertThat(parameter.isNamePresent()).isTrue(); assertThat(parameter.getName()).isEqualTo("memberIds");
        var client = mock(UserServiceClient.class); var repository = mock(PrivateNoteRepository.class);
        when(client.getContactMember(7L)).thenReturn(new PrivateNoteModels.ContactMember(7L, "synthetic", true, false));
        when(repository.blockedAuthors(eq(7L), anyList())).thenReturn(List.of());
        var original = spy(new F18LegacyBlockedAuthorsService(repository, client));
        var sourceReached = new AtomicBoolean(); var sourceBound = new AtomicReference<List<Long>>();
        doAnswer(call -> { sourceReached.set(true); List<Long> value = call.getArgument(1);
            sourceBound.set(value == null ? null : new ArrayList<>(value)); return call.callRealMethod();
        }).when(original).blockedAuthors(eq(7L), any());

        var members = mock(ContactMemberQuery.class); var blocks = mock(NoteBlockStatusReader.class);
        when(members.get(7L)).thenReturn(new ContactMember(7L, "synthetic", true, false));
        when(blocks.blockedAuthors(eq(7L), anyList())).thenReturn(List.of());
        var candidate = spy(new ReadBlockedAuthors(members, blocks));
        var candidateReached = new AtomicBoolean(); var candidateBound = new AtomicReference<List<Long>>();
        doAnswer(call -> { candidateReached.set(true); List<Long> value = call.getArgument(1);
            candidateBound.set(value == null ? null : new ArrayList<>(value)); return call.callRealMethod();
        }).when(candidate).read(eq(7L), any());

        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            var before = request(original(original, mapper), parameters);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L, null, List.of()));
            var after = request(candidate(candidate, mapper), parameters);
            JsonNode expected = mapper.readTree(before.getResponse().getContentAsByteArray());
            JsonNode actual = mapper.readTree(after.getResponse().getContentAsByteArray());
            assertThat(before.getResponse().getStatus()).as(label).isEqualTo(status);
            assertThat(after.getResponse().getStatus()).as(label).isEqualTo(status);
            assertThat(actual).as(label).isEqualTo(expected);
            assertThat(expected).isEqualTo(mapper.readTree(expectedBody(bodyKind)));
            assertThat(sourceReached.get()).isEqualTo(reached); assertThat(candidateReached.get()).isEqualTo(reached);
            assertThat(sourceBound.get()).isEqualTo(raw); assertThat(candidateBound.get()).isEqualTo(raw);
            int queries = status == 200 ? 1 : 0;
            assertThat(mockingDetails(client).getInvocations()).hasSize(queries);
            assertThat(mockingDetails(repository).getInvocations()).hasSize(queries);
            assertThat(mockingDetails(members).getInvocations()).hasSize(queries);
            assertThat(mockingDetails(blocks).getInvocations()).hasSize(queries);
            if (queries == 1) {
                List<Long> distinct = raw.stream().distinct().toList();
                assertThat(mockingDetails(repository).getInvocations().iterator().next().getArgument(1, List.class)).isEqualTo(distinct);
                assertThat(mockingDetails(blocks).getInvocations().iterator().next().getArgument(1, List.class)).isEqualTo(distinct);
            }
            cache(before); cache(after);
        } finally { SecurityContextHolder.clearContext(); }
    }

    @ParameterizedTest @ValueSource(longs = {8, Long.MAX_VALUE})
    void givenIntegralAuthorIds__whenBothMvcSerialize__thenExactOneFieldAndRawLongAgree(long id) throws Exception {
        var client = mock(UserServiceClient.class); var repository = mock(PrivateNoteRepository.class);
        when(client.getContactMember(7L)).thenReturn(new PrivateNoteModels.ContactMember(7L, "synthetic", true, false));
        when(repository.blockedAuthors(7L, List.of(id))).thenReturn(List.of(id));
        var blocks = mock(NoteBlockStatusReader.class); when(blocks.blockedAuthors(7L, List.of(id))).thenReturn(List.of(id));
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            var before = request(original(new F18LegacyBlockedAuthorsService(repository, client), mapper), new String[]{Long.toString(id)});
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L, null, List.of()));
            var after = request(candidate(new ReadBlockedAuthors(value -> new ContactMember(value, "synthetic", true, false), blocks), mapper), new String[]{Long.toString(id)});
            assertThat(after.getResponse().getStatus()).isEqualTo(200); assertThat(before.getResponse().getStatus()).isEqualTo(200);
            JsonNode body = mapper.readTree(after.getResponse().getContentAsByteArray());
            assertThat(body).isEqualTo(mapper.readTree(before.getResponse().getContentAsByteArray()));
            assertThat(body.size()).isEqualTo(3); assertThat(body.get("data").size()).isOne();
            assertThat(body.get("data").get("memberIds").get(0).isIntegralNumber()).isTrue();
            assertThat(body.get("data").get("memberIds").get(0).longValue()).isEqualTo(id);
            cache(before); cache(after);
        } finally { SecurityContextHolder.clearContext(); }
    }

    private MockMvc original(F18LegacyBlockedAuthorsService service, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new F18LegacyBlockedAuthorsController(service))
                .setControllerAdvice(new PrivateNoteExceptionAdvice(), new GlobalExceptionRestAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).addFilters(new PrivateNoteHttpBoundary()).build();
    }
    private MockMvc candidate(ReadBlockedAuthors service, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new NoteBlockStatusController(service))
                .setControllerAdvice(new NoteBlockStatusExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }
    private MvcResult request(MockMvc mvc, String[] values) throws Exception {
        var request = get("/api/v1/notes/blocks/status").header("X-User-Id", "7");
        if (values != null) request.param("memberIds", values);
        return mvc.perform(request).andReturn();
    }
    private void cache(MvcResult result) {
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }
    private String expectedBody(String kind) {
        return switch (kind) {
            case "missing" -> "{\"code\":400,\"message\":\"필수 요청 파라미터 'memberIds'(이)가 누락되었습니다.\",\"data\":null}";
            case "invalid" -> "{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}";
            case "list" -> "{\"message\":\"작성자 식별자는 1~100개의 양수여야 합니다.\"}";
            case "success" -> "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"memberIds\":[]}}";
            default -> throw new IllegalArgumentException("Unknown synthetic body contract");
        };
    }
    static Stream<Arguments> bindings() {
        return Stream.of(Arguments.of("missing", null, false, null, 400, "missing"),
                Arguments.of("csv", new String[]{"8,9"}, true, List.of(8L, 9L), 200, "success"),
                Arguments.of("empty", new String[]{""}, true, List.of(), 400, "list"),
                Arguments.of("space", new String[]{" "}, true, Arrays.asList((Long) null), 400, "list"),
                Arguments.of("csv-hole", new String[]{"8,,9"}, true, Arrays.asList(8L, null, 9L), 400, "list"),
                Arguments.of("csv-space", new String[]{"8, 9"}, true, List.of(8L, 9L), 200, "success"),
                Arguments.of("repeated", new String[]{"8", "9"}, true, List.of(8L, 9L), 200, "success"),
                Arguments.of("repeated-same", new String[]{"8", "8"}, true, List.of(8L, 8L), 200, "success"),
                Arguments.of("csv-mixed", new String[]{"8,9", "10"}, false, null, 400, "invalid"),
                Arguments.of("empty-repeated", new String[]{"", "8"}, true, Arrays.asList(null, 8L), 400, "list"),
                Arguments.of("invalid", new String[]{"abc"}, false, null, 400, "invalid"),
                Arguments.of("overflow", new String[]{"9223372036854775808"}, false, null, 400, "invalid"));
    }
}
