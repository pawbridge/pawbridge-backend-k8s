package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.note.application.RemoveNoteBlock;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockRemoval;
import com.pawbridge.backend.community.internal.note.web.NoteBlockRemoveController;
import com.pawbridge.backend.community.internal.note.web.NoteBlockRemoveExceptionHandler;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.F24LegacyNoteBlockRemoveController;
import com.pawbridge.communityservice.contact.F24LegacyNoteBlockRemoveExceptionAdvice;
import com.pawbridge.communityservice.contact.F24LegacyNoteBlockRemoveService;
import com.pawbridge.communityservice.contact.PrivateNoteHttpBoundary;
import com.pawbridge.communityservice.contact.PrivateNoteModels;
import com.pawbridge.communityservice.contact.PrivateNoteRepository;
import com.pawbridge.communityservice.exception.common.GlobalExceptionRestAdvice;
import feign.FeignException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;

/** Prepared only. All comparisons require actual original observations; recording JDBC/TM prove no real SQL/JpaTM. */
class F24LegacyNoteBlockRemoveMvcTest {
    @ParameterizedTest
    @MethodSource("paths")
    void givenMeasuredOriginalMemberPath__whenBothDeleteMvcBind__thenKeepExactVoidWireArgumentsAndTransactionPhases(
            String label, String path, MeasuredUnblock expected) throws Exception {
        assertThat(expected).as("UNMEASURED F24 original member path '%s'; fill only from complete original observation", label)
                .isNotNull();
        var parameter = F24LegacyNoteBlockRemoveController.class
                .getMethod("unblock", long.class, long.class).getParameters()[1];
        assertThat(parameter.getAnnotation(PathVariable.class).value()).isEqualTo("member");
        var fixture = new Fixture();
        var original = spy(fixture.original);
        var sourceBound = new AtomicReference<Long>();
        doAnswer(call -> { sourceBound.set(call.getArgument(1, Long.class)); call.callRealMethod(); return null; })
                .when(original).unblock(anyLong(), anyLong());
        var candidate = spy(fixture.candidate);
        var candidateBound = new AtomicReference<Long>();
        doAnswer(call -> { candidateBound.set(call.getArgument(1, Long.class)); call.callRealMethod(); return null; })
                .when(candidate).remove(anyLong(), anyLong());
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            var mapper = context.getBean(ObjectMapper.class);
            var before = request(original(original, mapper), path);
            authenticate();
            var after = request(candidate(candidate, mapper), path);
            assertThat(before.getResponse().getStatus()).as(label).isEqualTo(expected.status());
            assertThat(after.getResponse().getStatus()).as(label).isEqualTo(expected.status());
            assertThat(body(before)).isEqualTo(expected.body());
            assertThat(body(after)).isEqualTo(expected.body());
            assertThat(sourceBound.get() != null).isEqualTo(expected.reached());
            assertThat(candidateBound.get() != null).isEqualTo(expected.reached());
            assertThat(sourceBound.get()).isEqualTo(expected.target());
            assertThat(candidateBound.get()).isEqualTo(expected.target());
            fixture.assertSidesAgree();
            assertThat(fixture.source.phases).containsExactlyElementsOf(expected.phases());
            assertThat(fixture.source.jdbc.calls).hasSize(expected.sqlCalls());
            assertThat(mockingDetails(fixture.client).getInvocations()).hasSize(expected.currentCalls());
            assertThat(fixture.source.transactions.begins).isEqualTo(expected.begins());
            assertThat(fixture.source.transactions.commits).isEqualTo(expected.commits());
            assertThat(fixture.source.transactions.rollbacks).isEqualTo(expected.rollbacks());
            cache(before);
            cache(after);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void givenPositiveRepeatedSelfOrUnlookedUpTarget__whenBothMvcRemoveZeroRows__thenKeepObservedEmpty200AndOnlyCurrentLookupAfterOrderedLocks() throws Exception {
        MeasuredUnblock measured = originalCanonical();
        var fixture = new Fixture();
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            var mapper = context.getBean(ObjectMapper.class);
            var original = original(fixture.original, mapper);
            var candidate = candidate(fixture.candidate, mapper);
            for (long target : new long[]{3L, 3L, 7L, 999L}) {
                fixture.clearRecorded();
                var before = request(original, Long.toString(target));
                authenticate();
                var after = request(candidate, Long.toString(target));
                assertThat(before.getResponse().getStatus()).isEqualTo(measured.status()).isEqualTo(200);
                assertThat(after.getResponse().getStatus()).isEqualTo(measured.status());
                assertThat(body(before)).isEqualTo(measured.body()).isEmpty();
                assertThat(body(after)).isEqualTo(measured.body());
                fixture.assertSidesAgree();
                long first = Math.min(7L, target), second = Math.max(7L, target);
                List<String> expected = target == 7L
                        ? List.of("begin", "insert:7", "lock:7", "current:7", "delete:7:7", "commit")
                        : List.of("begin", "insert:" + first, "lock:" + first, "insert:" + second, "lock:" + second,
                                "current:7", "delete:7:" + target, "commit");
                assertThat(fixture.source.phases).containsExactlyElementsOf(expected);
                verify(fixture.client).getContactMember(7L);
                verify(fixture.members).get(7L);
                verifyNoMoreInteractions(fixture.client, fixture.members);
                cache(before);
                cache(after);
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void givenPendingCurrentAfterMemberLocks__whenBothMvcReject__thenReturnOriginal403RollbackAndNeverDeleteOrLookupTarget() throws Exception {
        originalCanonical();
        var fixture = new Fixture();
        fixture.inactive = true;
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            var mapper = context.getBean(ObjectMapper.class);
            var before = request(original(fixture.original, mapper), "8");
            authenticate();
            var after = request(candidate(fixture.candidate, mapper), "8");
            assertWireError(before, after, mapper, 403, "이용할 수 없는 회원입니다.");
            fixture.assertSidesAgree();
            assertThat(fixture.source.phases).containsExactly("begin", "insert:7", "lock:7", "insert:8", "lock:8", "current:7", "rollback");
            verify(fixture.client).getContactMember(7L);
            verify(fixture.members).get(7L);
            verifyNoMoreInteractions(fixture.client, fixture.members);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "DAO", "BEGIN", "COMMIT", "TIMEOUT", "RUNTIME"})
    void givenUserDaoOrTransactionRuntimeFailure__whenBothMvcHandle__thenPreserveOriginalMemberStorageOrGeneric503WithoutReadSideReclassification(String phase) throws Exception {
        originalCanonical();
        var fixture = new Fixture();
        fixture.fail(phase);
        String message = phase.equals("USER") ? "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
                : phase.equals("DAO") ? "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."
                : "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            var mapper = context.getBean(ObjectMapper.class);
            var before = request(original(fixture.original, mapper), "8");
            authenticate();
            var after = request(candidate(fixture.candidate, mapper), "8");
            assertWireError(before, after, mapper, 503, message);
            fixture.assertSidesAgree();
            List<String> expected = phase.equals("BEGIN") ? List.of("begin")
                    : phase.equals("USER") ? List.of("begin", "insert:7", "lock:7", "insert:8", "lock:8", "current:7", "rollback")
                    : List.of("begin", "insert:7", "lock:7", "insert:8", "lock:8", "current:7", "delete:7:8",
                            phase.equals("COMMIT") ? "commit" : "rollback");
            assertThat(fixture.source.phases).containsExactlyElementsOf(expected);
            assertThat(fixture.source.transactions.rollbacks).isEqualTo(phase.equals("BEGIN") || phase.equals("COMMIT") ? 0 : 1);
            if (!phase.equals("BEGIN")) {
                verify(fixture.client).getContactMember(7L);
                verify(fixture.members).get(7L);
            }
            verifyNoMoreInteractions(fixture.client, fixture.members);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static MeasuredUnblock originalCanonical() {
        MeasuredUnblock value = (MeasuredUnblock) paths().findFirst().orElseThrow().get()[2];
        assertThat(value).as("UNMEASURED F24 original empty-success observation; do not infer from candidate").isNotNull();
        return value;
    }

    private MockMvc original(F24LegacyNoteBlockRemoveService service, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new F24LegacyNoteBlockRemoveController(service))
                .setControllerAdvice(new F24LegacyNoteBlockRemoveExceptionAdvice(), new GlobalExceptionRestAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new PrivateNoteHttpBoundary()).build();
    }

    private MockMvc candidate(RemoveNoteBlock service, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new NoteBlockRemoveController(service))
                .setControllerAdvice(new NoteBlockRemoveExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    private MvcResult request(MockMvc mvc, String target) throws Exception {
        return mvc.perform(delete("/api/v1/notes/blocks/" + target).header("X-User-Id", "7")).andReturn();
    }

    private static void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L, null, List.of()));
    }

    private static String body(MvcResult result) throws Exception { return result.getResponse().getContentAsString(StandardCharsets.UTF_8); }

    private void cache(MvcResult result) {
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }

    private void assertWireError(MvcResult before, MvcResult after, ObjectMapper mapper, int status, String message) throws Exception {
        var expected = mapper.valueToTree(Map.of("message", message));
        assertThat(before.getResponse().getStatus()).isEqualTo(status);
        assertThat(after.getResponse().getStatus()).isEqualTo(status);
        assertThat(mapper.readTree(body(before))).isEqualTo(expected);
        assertThat(mapper.readTree(body(after))).isEqualTo(expected);
        cache(before);
        cache(after);
    }

    private record SqlCall(String operation, String sql, List<Object> arguments) { }

    private static final class Fixture {
        private final Side source = new Side(), adapted = new Side();
        private final UserServiceClient client = mock(UserServiceClient.class);
        private final ContactMemberQuery members = mock(ContactMemberQuery.class);
        private final F24LegacyNoteBlockRemoveService original;
        private final RemoveNoteBlock candidate;
        private boolean inactive;
        private RuntimeException originalUserFailure, candidateUserFailure;

        private Fixture() {
            when(client.getContactMember(7L)).thenAnswer(call -> {
                current(source);
                if (originalUserFailure != null) throw originalUserFailure;
                return new PrivateNoteModels.ContactMember(7L, "synthetic", !inactive, inactive);
            });
            when(members.get(7L)).thenAnswer(call -> {
                current(adapted);
                if (candidateUserFailure != null) throw candidateUserFailure;
                return new ContactMember(7L, "synthetic", !inactive, inactive);
            });
            original = new F24LegacyNoteBlockRemoveService(new PrivateNoteRepository(source.jdbc), client, source.transaction());
            candidate = new RemoveNoteBlock(members, new JdbcNoteBlockRemoval(adapted.jdbc, adapted.transaction()));
        }

        private void current(Side side) { assertThat(side.transactions.active).isTrue(); side.phases.add("current:7"); }

        private void clearRecorded() {
            for (Side side : List.of(source, adapted)) {
                side.phases.clear(); side.jdbc.calls.clear();
                side.transactions.begins = side.transactions.commits = side.transactions.rollbacks = 0;
            }
            clearInvocations(client, members);
        }

        private void assertSidesAgree() {
            assertThat(adapted.jdbc.calls).containsExactlyElementsOf(source.jdbc.calls);
            assertThat(adapted.phases).containsExactlyElementsOf(source.phases);
            assertThat(mockingDetails(members).getInvocations().size()).isEqualTo(mockingDetails(client).getInvocations().size());
            assertThat(adapted.transactions.begins).isEqualTo(source.transactions.begins);
            assertThat(adapted.transactions.commits).isEqualTo(source.transactions.commits);
            assertThat(adapted.transactions.rollbacks).isEqualTo(source.transactions.rollbacks);
            assertThat(source.transactions.active).isFalse();
            assertThat(adapted.transactions.active).isFalse();
        }

        private void fail(String phase) {
            if (phase.equals("USER")) {
                var feign = mock(FeignException.class); when(feign.status()).thenReturn(500);
                originalUserFailure = feign;
                candidateUserFailure = new ContactMemberUnavailableException(new IllegalStateException("synthetic-user"));
                return;
            }
            for (Side side : List.of(source, adapted)) {
                switch (phase) {
                    case "BEGIN" -> side.transactions.beginFailure = new CannotCreateTransactionException("synthetic-begin");
                    case "COMMIT" -> side.transactions.commitFailure = new TransactionSystemException("synthetic-commit");
                    case "DAO" -> side.jdbc.deleteFailure = new DataRetrievalFailureException("synthetic-delete");
                    case "TIMEOUT" -> side.jdbc.deleteFailure = new TransactionTimedOutException("synthetic-timeout");
                    case "RUNTIME" -> side.jdbc.deleteFailure = new IllegalStateException("synthetic-runtime");
                    default -> throw new AssertionError("Unknown prepared phase");
                }
            }
        }
    }

    private static final class Side {
        private final List<String> phases = new ArrayList<>();
        private final RecordingTransactions transactions = new RecordingTransactions(phases);
        private final RecordingJdbc jdbc = new RecordingJdbc(transactions, phases);

        private TransactionTemplate transaction() { var value = new TransactionTemplate(transactions); value.setTimeout(15); return value; }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final RecordingTransactions transactions;
        private final List<String> phases;
        private final List<SqlCall> calls = new ArrayList<>();
        private RuntimeException deleteFailure;

        private RecordingJdbc(RecordingTransactions transactions, List<String> phases) { this.transactions = transactions; this.phases = phases; }

        @Override
        public int update(String sql, Object... arguments) {
            String normalized = sql.replaceAll("\\s+", " ").strip();
            String operation = normalized.startsWith("INSERT INTO pawbridge_community.private_note_members") ? "insert"
                    : normalized.startsWith("DELETE FROM pawbridge_community.private_note_blocks") ? "delete" : "unexpected";
            record(operation, normalized, arguments);
            if (operation.equals("delete") && deleteFailure != null) throw deleteFailure;
            return 0;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... arguments) {
            record("lock", sql.replaceAll("\\s+", " ").strip(), arguments);
            return requiredType.cast(arguments[0]);
        }

        private void record(String operation, String sql, Object[] arguments) {
            assertThat(transactions.active).isTrue();
            assertThat(operation).isNotEqualTo("unexpected");
            calls.add(new SqlCall(operation, sql, List.copyOf(Arrays.asList(arguments))));
            phases.add(operation + ":" + String.join(":", Arrays.stream(arguments).map(Object::toString).toList()));
        }
    }

    private static final class RecordingTransactions implements PlatformTransactionManager {
        private final List<String> phases;
        private boolean active;
        private int begins, commits, rollbacks;
        private RuntimeException beginFailure, commitFailure;

        private RecordingTransactions(List<String> phases) { this.phases = phases; }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            assertThat(active).isFalse();
            assertThat(definition.isReadOnly()).isFalse();
            assertThat(definition.getTimeout()).isEqualTo(15);
            begins++; phases.add("begin");
            if (beginFailure != null) throw beginFailure;
            active = true;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            active = false; commits++; phases.add("commit");
            if (commitFailure != null) throw commitFailure;
        }

        @Override
        public void rollback(TransactionStatus status) { active = false; rollbacks++; phases.add("rollback"); }
    }

    private record MeasuredUnblock(int status, boolean reached, Long target, String body,
                                   int currentCalls, int sqlCalls, int begins, int commits, int rollbacks,
                                   List<String> phases) { }

    static Stream<Arguments> paths() {
        // Filled only from complete original-only JSON SHA256 9cb8577c8f02fafd042251d7ead48973c8aed4cde5504dffc51fb122dabdecb8.
        return Stream.of(Arguments.of("canonical8", "8", new MeasuredUnblock(200, true, 8L, "", 1, 5, 1, 1, 0, List.of("begin", "insert:7", "lock:7", "insert:8", "lock:8", "current:7", "delete:7:8", "commit"))),
                Arguments.of("plus8", "+8", new MeasuredUnblock(200, true, 8L, "", 1, 5, 1, 1, 0, List.of("begin", "insert:7", "lock:7", "insert:8", "lock:8", "current:7", "delete:7:8", "commit"))),
                Arguments.of("leading-zero8", "0008", new MeasuredUnblock(200, true, 8L, "", 1, 5, 1, 1, 0, List.of("begin", "insert:7", "lock:7", "insert:8", "lock:8", "current:7", "delete:7:8", "commit"))),
                Arguments.of("zero", "0", new MeasuredUnblock(401, true, 0L, "{\"message\":\"로그인이 필요합니다.\"}", 0, 0, 0, 0, 0, List.of())),
                Arguments.of("negative", "-1", new MeasuredUnblock(401, true, -1L, "{\"message\":\"로그인이 필요합니다.\"}", 0, 0, 0, 0, 0, List.of())),
                Arguments.of("overflow", "9223372036854775808", new MeasuredUnblock(400, false, null, "{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", 0, 0, 0, 0, 0, List.of())));
    }
}
