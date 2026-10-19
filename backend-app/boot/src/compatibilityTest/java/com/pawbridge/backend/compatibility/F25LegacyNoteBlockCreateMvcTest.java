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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.note.application.CreateNoteBlock;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockCreation;
import com.pawbridge.backend.community.internal.note.web.NoteBlockCreateController;
import com.pawbridge.backend.community.internal.note.web.NoteBlockCreateExceptionHandler;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.F25LegacyNoteBlockCreateController;
import com.pawbridge.communityservice.contact.F25LegacyNoteBlockCreateExceptionAdvice;
import com.pawbridge.communityservice.contact.F25LegacyNoteBlockCreateService;
import com.pawbridge.communityservice.contact.PrivateNoteHttpBoundary;
import com.pawbridge.communityservice.contact.PrivateNoteModels;
import com.pawbridge.communityservice.contact.PrivateNoteRepository;
import com.pawbridge.communityservice.exception.common.GlobalExceptionRestAdvice;
import feign.FeignException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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

/** Binding provider measured from original-only observations. Recording JDBC/TM and mock User are not actual PG/JpaTM proof. */
class F25LegacyNoteBlockCreateMvcTest {
    private static final Instant FIRST = Instant.parse("2031-01-01T00:00:00.123456Z");
    private static final Instant LATER = Instant.parse("2031-01-02T00:00:00.654321Z");

    @ParameterizedTest
    @MethodSource("paths")
    void givenMeasuredOriginalMemberPath__whenBothPutMvcBind__thenKeepExactVoidWireAndIdSelfOrOrderedActiveClockPhases(
            String label, String path, MeasuredBlock expected) throws Exception {
        assertThat(expected).as("UNMEASURED F25 original path '%s'; fill only from complete original-only observations", label).isNotNull();
        var parameter = F25LegacyNoteBlockCreateController.class.getMethod("block", long.class, long.class).getParameters()[1];
        assertThat(parameter.getAnnotation(PathVariable.class).value()).isEqualTo("member");
        var fixture = new Fixture();
        var original = spy(fixture.original);
        var originalBound = new AtomicReference<Long>();
        doAnswer(call -> { originalBound.set(call.getArgument(1, Long.class)); call.callRealMethod(); return null; })
                .when(original).block(anyLong(), anyLong());
        var candidate = spy(fixture.candidate);
        var candidateBound = new AtomicReference<Long>();
        doAnswer(call -> { candidateBound.set(call.getArgument(1, Long.class)); call.callRealMethod(); return null; })
                .when(candidate).create(anyLong(), anyLong());
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            var mapper = context.getBean(ObjectMapper.class);
            var before = request(original(original, mapper), path);
            authenticate();
            var after = request(candidate(candidate, mapper), path);
            for (var result : List.of(before, after)) {
                assertThat(result.getResponse().getStatus()).as(label).isEqualTo(expected.status());
                assertThat(body(result)).isEqualTo(expected.body());
                cache(result);
            }
            assertThat(originalBound.get() != null).isEqualTo(expected.reached());
            assertThat(candidateBound.get() != null).isEqualTo(expected.reached());
            assertThat(originalBound.get()).isEqualTo(expected.target());
            assertThat(candidateBound.get()).isEqualTo(expected.target());
            fixture.assertSidesAgree();
            assertThat(fixture.source.phases).containsExactlyElementsOf(expected.phases());
            assertThat(fixture.source.jdbc.calls).hasSize(expected.sqlCalls());
            assertThat(memberCalls(fixture.client, 7L)).isEqualTo(expected.currentCalls());
            assertThat(targetCalls(fixture.client)).isEqualTo(expected.targetCalls());
            assertThat(fixture.source.clock.calls).isEqualTo(expected.clockCalls());
            assertThat(fixture.source.transactions.begins).isEqualTo(expected.begins());
            assertThat(fixture.source.transactions.commits).isEqualTo(expected.commits());
            assertThat(fixture.source.transactions.rollbacks).isEqualTo(expected.rollbacks());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void givenAnExistingOutgoingPairAndAnAdvancingClock__whenBothMvcRepeatBlock__thenStillCheckBothMembersAndSampleClockWithoutUpdatingOriginalTimestamp() throws Exception {
        MeasuredBlock measured = originalCanonical();
        var fixture = new Fixture();
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            var mapper = context.getBean(ObjectMapper.class);
            var original = original(fixture.original, mapper);
            var candidate = candidate(fixture.candidate, mapper);
            for (Instant now : List.of(FIRST, LATER)) {
                fixture.clearRecorded();
                fixture.source.clock.now = fixture.adapted.clock.now = now;
                var before = request(original, "3");
                authenticate();
                var after = request(candidate, "3");
                for (var result : List.of(before, after)) {
                    assertThat(result.getResponse().getStatus()).isEqualTo(measured.status()).isEqualTo(200);
                    assertThat(body(result)).isEqualTo(measured.body()).isEmpty();
                    cache(result);
                }
                fixture.assertSidesAgree();
                assertThat(fixture.source.phases).containsExactly("begin", "insert:3", "lock:3", "insert:7", "lock:7",
                        "current:7", "target:3", "clock", "block:7:3", "commit");
                assertThat(fixture.source.clock.calls).isOne();
                assertThat(fixture.source.jdbc.calls.getLast().arguments()).containsExactly(7L, 3L, Timestamp.from(now));
                assertThat(fixture.source.jdbc.storedCreatedAt).containsExactlyEntriesOf(Map.of(new Pair(7L, 3L), FIRST));
                assertThat(fixture.source.jdbc.blockRowCounts).containsExactly(now.equals(FIRST) ? 1 : 0);
                verify(fixture.client).getContactMember(7L);
                verify(fixture.client).getContactMember(3L);
                verify(fixture.members).get(7L);
                verify(fixture.members).get(3L);
                verifyNoMoreInteractions(fixture.client, fixture.members);
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {7L, 8L})
    void givenAnInactiveCurrentOrTargetAfterMemberLocks__whenBothMvcReject__thenRollBackBeforeClockAndOutgoingInsertWithCurrentFirst(long inactiveId) throws Exception {
        originalCanonical();
        var fixture = new Fixture();
        fixture.inactiveId = inactiveId;
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            var mapper = context.getBean(ObjectMapper.class);
            var before = request(original(fixture.original, mapper), "8");
            authenticate();
            var after = request(candidate(fixture.candidate, mapper), "8");
            assertWireError(before, after, mapper, 403, "이용할 수 없는 회원입니다.");
            fixture.assertSidesAgree();
            var expected = new ArrayList<>(List.of("begin", "insert:7", "lock:7", "insert:8", "lock:8", "current:7"));
            if (inactiveId == 8L) expected.add("target:8");
            expected.add("rollback");
            assertThat(fixture.source.phases).containsExactlyElementsOf(expected);
            assertThat(fixture.source.clock.calls).isZero();
            assertThat(fixture.source.jdbc.calls).hasSize(4);
            assertThat(targetCalls(fixture.client)).isEqualTo(inactiveId == 8L ? 1 : 0);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER_CURRENT", "USER_TARGET", "DAO", "BEGIN", "COMMIT", "TIMEOUT", "CLOCK"})
    void givenCurrentTargetDaoTransactionOrClockFailure__whenBothMvcHandle__thenPreserveMemberStorageOrGeneric503AndStopAtTheSamePhase(String phase) throws Exception {
        originalCanonical();
        var fixture = new Fixture();
        fixture.fail(phase);
        String message = phase.startsWith("USER_") ? "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
                : phase.equals("DAO") ? "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."
                : "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            var mapper = context.getBean(ObjectMapper.class);
            var before = request(original(fixture.original, mapper), "8");
            authenticate();
            var after = request(candidate(fixture.candidate, mapper), "8");
            assertWireError(before, after, mapper, 503, message);
            fixture.assertSidesAgree();
            var expected = new ArrayList<>(List.of("begin"));
            if (!phase.equals("BEGIN")) {
                expected.addAll(List.of("insert:7", "lock:7", "insert:8", "lock:8", "current:7"));
                if (!phase.equals("USER_CURRENT")) expected.add("target:8");
                if (!phase.startsWith("USER_")) expected.add("clock");
                if (!phase.startsWith("USER_") && !phase.equals("CLOCK")) expected.add("block:7:8");
                expected.add(phase.equals("COMMIT") ? "commit" : "rollback");
            }
            assertThat(fixture.source.phases).containsExactlyElementsOf(expected);
            assertThat(memberCalls(fixture.client, 7L)).isEqualTo(phase.equals("BEGIN") ? 0 : 1);
            assertThat(targetCalls(fixture.client)).isEqualTo(List.of("BEGIN", "USER_CURRENT").contains(phase) ? 0 : 1);
            assertThat(fixture.source.clock.calls).isEqualTo(phase.equals("BEGIN") || phase.startsWith("USER_") ? 0 : 1);
            assertThat(fixture.source.transactions.rollbacks).isEqualTo(List.of("BEGIN", "COMMIT").contains(phase) ? 0 : 1);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static MeasuredBlock originalCanonical() {
        MeasuredBlock value = (MeasuredBlock) paths().findFirst().orElseThrow().get()[2];
        assertThat(value).as("UNMEASURED F25 original empty-success observation; do not infer from candidate").isNotNull();
        return value;
    }

    private MockMvc original(F25LegacyNoteBlockCreateService service, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new F25LegacyNoteBlockCreateController(service))
                .setControllerAdvice(new F25LegacyNoteBlockCreateExceptionAdvice(), new GlobalExceptionRestAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new PrivateNoteHttpBoundary()).build();
    }

    private MockMvc candidate(CreateNoteBlock service, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new NoteBlockCreateController(service))
                .setControllerAdvice(new NoteBlockCreateExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    private MvcResult request(MockMvc mvc, String target) throws Exception {
        return mvc.perform(put("/api/v1/notes/blocks/" + target).header("X-User-Id", "7")).andReturn();
    }

    private static void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L, null, List.of()));
    }

    private static long memberCalls(Object client, long id) {
        return mockingDetails(client).getInvocations().stream().filter(call -> call.getArgument(0, Long.class) == id).count();
    }

    private static long targetCalls(Object client) {
        return mockingDetails(client).getInvocations().stream().filter(call -> call.getArgument(0, Long.class) != 7L).count();
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
        cache(before); cache(after);
    }

    private record SqlCall(String operation, String sql, List<Object> arguments) { }
    private record Pair(long current, long target) { }

    private static final class Fixture {
        private final Side source = new Side(), adapted = new Side();
        private final UserServiceClient client = mock(UserServiceClient.class);
        private final ContactMemberQuery members = mock(ContactMemberQuery.class);
        private final F25LegacyNoteBlockCreateService original;
        private final CreateNoteBlock candidate;
        private long inactiveId;
        private String userFailurePhase;

        private Fixture() {
            when(client.getContactMember(anyLong())).thenAnswer(call -> {
                long id = call.getArgument(0, Long.class);
                currentOrTarget(source, id);
                if (failsUser(id)) { var failure = mock(FeignException.class); when(failure.status()).thenReturn(500); throw failure; }
                return new PrivateNoteModels.ContactMember(id, "synthetic", id != inactiveId, id == inactiveId);
            });
            when(members.get(anyLong())).thenAnswer(call -> {
                long id = call.getArgument(0, Long.class);
                currentOrTarget(adapted, id);
                if (failsUser(id)) throw new ContactMemberUnavailableException(new IllegalStateException("synthetic-user"));
                return new ContactMember(id, "synthetic", id != inactiveId, id == inactiveId);
            });
            original = new F25LegacyNoteBlockCreateService(new PrivateNoteRepository(source.jdbc), client, source.transaction(), source.clock);
            candidate = new CreateNoteBlock(members, new JdbcNoteBlockCreation(adapted.jdbc, adapted.transaction()), adapted.clock);
        }

        private boolean failsUser(long id) {
            return "USER_CURRENT".equals(userFailurePhase) && id == 7L || "USER_TARGET".equals(userFailurePhase) && id != 7L;
        }

        private void currentOrTarget(Side side, long id) {
            assertThat(side.transactions.active).isTrue();
            side.phases.add((id == 7L ? "current:" : "target:") + id);
        }

        private void clearRecorded() {
            for (Side side : List.of(source, adapted)) {
                side.phases.clear(); side.jdbc.calls.clear(); side.jdbc.blockRowCounts.clear(); side.clock.calls = 0;
                side.transactions.begins = side.transactions.commits = side.transactions.rollbacks = 0;
            }
            clearInvocations(client, members);
        }

        private void assertSidesAgree() {
            assertThat(adapted.jdbc.calls).containsExactlyElementsOf(source.jdbc.calls);
            assertThat(adapted.phases).containsExactlyElementsOf(source.phases);
            assertThat(adapted.jdbc.storedCreatedAt).containsExactlyEntriesOf(source.jdbc.storedCreatedAt);
            assertThat(adapted.jdbc.blockRowCounts).containsExactlyElementsOf(source.jdbc.blockRowCounts);
            assertThat(memberCalls(members, 7L)).isEqualTo(memberCalls(client, 7L));
            assertThat(targetCalls(members)).isEqualTo(targetCalls(client));
            assertThat(adapted.clock.calls).isEqualTo(source.clock.calls);
            assertThat(adapted.transactions.begins).isEqualTo(source.transactions.begins);
            assertThat(adapted.transactions.commits).isEqualTo(source.transactions.commits);
            assertThat(adapted.transactions.rollbacks).isEqualTo(source.transactions.rollbacks);
            assertThat(source.transactions.active).isFalse();
            assertThat(adapted.transactions.active).isFalse();
        }

        private void fail(String phase) {
            if (phase.startsWith("USER_")) { userFailurePhase = phase; return; }
            for (Side side : List.of(source, adapted)) {
                switch (phase) {
                    case "BEGIN" -> side.transactions.beginFailure = new CannotCreateTransactionException("synthetic-begin");
                    case "COMMIT" -> side.transactions.commitFailure = new TransactionSystemException("synthetic-commit");
                    case "DAO" -> side.jdbc.blockFailure = new DataRetrievalFailureException("synthetic-block");
                    case "TIMEOUT" -> side.jdbc.blockFailure = new TransactionTimedOutException("synthetic-timeout");
                    case "CLOCK" -> side.clock.failure = new IllegalStateException("synthetic-clock");
                    default -> throw new AssertionError("Unknown prepared failure phase");
                }
            }
        }
    }

    private static final class Side {
        private final List<String> phases = new ArrayList<>();
        private final RecordingTransactions transactions = new RecordingTransactions(phases);
        private final RecordingJdbc jdbc = new RecordingJdbc(transactions, phases);
        private final RecordingClock clock = new RecordingClock(transactions, phases);
        private TransactionTemplate transaction() { var value = new TransactionTemplate(transactions); value.setTimeout(15); return value; }
    }

    private static final class RecordingClock extends Clock {
        private final RecordingTransactions transactions;
        private final List<String> phases;
        private Instant now = FIRST;
        private int calls;
        private RuntimeException failure;
        private RecordingClock(RecordingTransactions transactions, List<String> phases) { this.transactions = transactions; this.phases = phases; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() {
            assertThat(transactions.active).isTrue(); calls++; phases.add("clock");
            if (failure != null) throw failure;
            return now;
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final RecordingTransactions transactions;
        private final List<String> phases;
        private final List<SqlCall> calls = new ArrayList<>();
        // Models ON CONFLICT only; this is not a database or transaction rollback oracle.
        private final Map<Pair, Instant> storedCreatedAt = new LinkedHashMap<>();
        private final List<Integer> blockRowCounts = new ArrayList<>();
        private RuntimeException blockFailure;
        private RecordingJdbc(RecordingTransactions transactions, List<String> phases) { this.transactions = transactions; this.phases = phases; }

        @Override public int update(String sql, Object... arguments) {
            String normalized = sql.replaceAll("\\s+", " ").strip();
            if (normalized.startsWith("INSERT INTO pawbridge_community.private_note_members")) {
                record("insert", normalized, arguments); return 0;
            }
            if (!normalized.startsWith("INSERT INTO pawbridge_community.private_note_blocks")) throw new AssertionError("Unexpected source/candidate SQL");
            record("block", normalized, arguments);
            assertThat(normalized).endsWith("ON CONFLICT DO NOTHING");
            assertThat(arguments[2]).isInstanceOf(Timestamp.class);
            if (blockFailure != null) throw blockFailure;
            var pair = new Pair((Long) arguments[0], (Long) arguments[1]);
            int affected = storedCreatedAt.putIfAbsent(pair, ((Timestamp) arguments[2]).toInstant()) == null ? 1 : 0;
            blockRowCounts.add(affected); return affected;
        }

        @Override public <T> T queryForObject(String sql, Class<T> requiredType, Object... arguments) {
            assertThat(requiredType).isEqualTo(Long.class);
            record("lock", sql.replaceAll("\\s+", " ").strip(), arguments);
            return requiredType.cast(arguments[0]);
        }

        private void record(String operation, String sql, Object[] arguments) {
            assertThat(transactions.active).isTrue();
            calls.add(new SqlCall(operation, sql, List.copyOf(Arrays.asList(arguments))));
            phases.add(operation + ":" + arguments[0] + (operation.equals("block") ? ":" + arguments[1] : ""));
        }
    }

    private static final class RecordingTransactions implements PlatformTransactionManager {
        private final List<String> phases;
        private boolean active;
        private int begins, commits, rollbacks;
        private RuntimeException beginFailure, commitFailure;
        private RecordingTransactions(List<String> phases) { this.phases = phases; }
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            assertThat(active).isFalse(); assertThat(definition.isReadOnly()).isFalse(); assertThat(definition.getTimeout()).isEqualTo(15);
            begins++; phases.add("begin"); if (beginFailure != null) throw beginFailure;
            active = true; return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) {
            active = false; commits++; phases.add("commit"); if (commitFailure != null) throw commitFailure;
        }
        @Override public void rollback(TransactionStatus status) { active = false; rollbacks++; phases.add("rollback"); }
    }

    private record MeasuredBlock(int status, boolean reached, Long target, String body,
                                 int currentCalls, int targetCalls, int clockCalls, int sqlCalls,
                                 int begins, int commits, int rollbacks, List<String> phases) { }

    static Stream<Arguments> paths() {
        // Filled only from complete original-only seven-input observation SHA256 7f4cea6e1da726d9b4fa54670ab08c1a9592773a337546965ba5ff654b9ec871.
        return Stream.of(Arguments.of("canonical8", "8", new MeasuredBlock(200, true, 8L, "", 1, 1, 1, 5, 1, 1, 0, List.of("begin", "insert:7", "lock:7", "insert:8", "lock:8", "current:7", "target:8", "clock", "block:7:8", "commit"))),
                Arguments.of("plus8", "+8", new MeasuredBlock(200, true, 8L, "", 1, 1, 1, 5, 1, 1, 0, List.of("begin", "insert:7", "lock:7", "insert:8", "lock:8", "current:7", "target:8", "clock", "block:7:8", "commit"))),
                Arguments.of("leading-zero8", "0008", new MeasuredBlock(200, true, 8L, "", 1, 1, 1, 5, 1, 1, 0, List.of("begin", "insert:7", "lock:7", "insert:8", "lock:8", "current:7", "target:8", "clock", "block:7:8", "commit"))),
                Arguments.of("zero", "0", new MeasuredBlock(401, true, 0L, "{\"message\":\"로그인이 필요합니다.\"}", 0, 0, 0, 0, 0, 0, 0, List.of())),
                Arguments.of("negative", "-1", new MeasuredBlock(401, true, -1L, "{\"message\":\"로그인이 필요합니다.\"}", 0, 0, 0, 0, 0, 0, 0, List.of())),
                Arguments.of("overflow", "9223372036854775808", new MeasuredBlock(400, false, null, "{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", 0, 0, 0, 0, 0, 0, 0, List.of())),
                Arguments.of("self7", "7", new MeasuredBlock(400, true, 7L, "{\"message\":\"자신을 차단할 수 없습니다.\"}", 0, 0, 0, 0, 0, 0, 0, List.of())));
    }
}
