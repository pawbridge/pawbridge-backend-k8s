package com.pawbridge.backend.user.internal.contact.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcContactMembersQueryTest {
    @ParameterizedTest
    @MethodSource("invalidLists")
    void given__invalid_raw_ids_before_deduplication__when__querying__then__reject_outside_transaction_and_typed_failure_boundary(
            String label, List<Long> ids) throws SQLException {
        var fixture = new Fixture();

        var failure = assertThrows(IllegalArgumentException.class, () -> fixture.query.getAll(ids));

        assertEquals("회원 조회 범위를 확인해 주세요.", failure.getMessage());
        assertEquals(0, fixture.transactions.begins);
        assertEquals(0, fixture.transactions.commits);
        assertEquals(0, fixture.transactions.rollbacks);
        assertEquals(0, fixture.jdbc.queries);
        verifyNoInteractions(fixture.row);
    }

    static Stream<Arguments> invalidLists() {
        return Stream.of(Arguments.of("null", null), Arguments.of("empty", List.of()),
                Arguments.of("22-distinct", LongStream.rangeClosed(1, 22).boxed().toList()),
                Arguments.of("22-duplicates", Collections.nCopies(22, 7L)),
                Arguments.of("null-element", Arrays.asList(7L, null)),
                Arguments.of("zero", List.of(7L, 0L)), Arguments.of("negative", List.of(-1L)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void given__exactly_21_raw_ids__when__querying__then__accept_once_and_return_a_projection_for_each_first_occurring_id_after_commit(
            boolean repeated) throws SQLException {
        var fixture = new Fixture();
        fixture.jdbc.rows.clear();
        List<Long> ids = repeated ? Collections.nCopies(21, 7L) : LongStream.rangeClosed(1, 21).boxed().toList();
        List<Long> distinctIds = repeated ? List.of(7L) : ids;

        var found = fixture.query.getAll(ids);

        assertEquals(distinctIds.stream().map(id -> new ContactMember(id, "탈퇴한 회원", false, false)).toList(), found);
        assertArrayEquals(distinctIds.toArray(), fixture.jdbc.arguments);
        assertEquals(sql(distinctIds.size()), fixture.jdbc.sql.replaceAll("\\s+", " ").strip());
        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertEquals(0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__unordered_active_pending_missing_and_duplicate_ids__when__querying__then__restore_input_order_and_raw_nicknames_without_partial_fallback() throws SQLException {
        var fixture = new Fixture();
        ResultSet whitespace = member(10, "  원문\n닉네임  ", false);
        ResultSet pending = member(7, "노출하지 않을 닉네임", true);
        ResultSet empty = member(9, "", false);
        fixture.jdbc.rows.clear();
        fixture.jdbc.rows.addAll(List.of(whitespace, pending, empty));
        var ids = new ArrayList<>(List.of(9L, 7L, 9L, 8L, 10L));

        var found = fixture.query.getAll(ids);

        assertEquals(List.of(new ContactMember(9L, "", true, false),
                new ContactMember(7L, "탈퇴한 회원", false, true),
                new ContactMember(8L, "탈퇴한 회원", false, false),
                new ContactMember(10L, "  원문\n닉네임  ", true, false)), found);
        assertEquals(List.of(9L, 7L, 9L, 8L, 10L), ids);
        assertArrayEquals(new Object[] {9L, 7L, 8L, 10L}, fixture.jdbc.arguments);
        assertEquals(sql(4), fixture.jdbc.sql.replaceAll("\\s+", " ").strip());
        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
        verify(pending, never()).getString("nickname");
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__batch_begin_query_mapping_or_commit_failure_then_recovery__when__querying__then__preserve_the_cause_as_typed_unavailable_without_partial_results(
            FailurePhase phase) throws SQLException {
        var fixture = new Fixture();
        RuntimeException failure = switch (phase) {
            case BEGIN -> new CannotCreateTransactionException("synthetic-private-begin");
            case QUERY -> new DataAccessResourceFailureException("synthetic-private-batch-query");
            case MAP -> new IllegalStateException("synthetic-private-mapping");
            case COMMIT -> new TransactionSystemException("synthetic-private-commit");
        };
        switch (phase) {
            case BEGIN -> fixture.transactions.startFailure = failure;
            case QUERY -> fixture.jdbc.failure = failure;
            case MAP -> when(fixture.row.getBoolean("pending")).thenThrow(failure);
            case COMMIT -> fixture.transactions.completionFailure = failure;
        }

        var unavailable = assertThrows(ContactMemberUnavailableException.class, () -> fixture.query.getAll(List.of(7L)));

        assertSame(failure, unavailable.getCause());
        assertEquals("Contact member lookup is unavailable.", unavailable.getMessage());
        assertEquals(phase == FailurePhase.BEGIN ? 0 : 1, fixture.jdbc.queries);
        assertEquals(phase == FailurePhase.QUERY || phase == FailurePhase.MAP ? 1 : 0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
        fixture.transactions.startFailure = null;
        fixture.transactions.completionFailure = null;
        fixture.jdbc.failure = null;
        doReturn(false).when(fixture.row).getBoolean("pending");

        assertEquals(List.of(new ContactMember(7L, "원문 닉네임", true, false)), fixture.query.getAll(List.of(7L)));
        assertEquals(phase == FailurePhase.BEGIN ? 1 : 2, fixture.jdbc.queries);
        assertFalse(fixture.transactions.active);
    }

    private static String sql(int ids) {
        return "SELECT u.user_id, u.nickname, d.user_id IS NOT NULL AS pending FROM pawbridge_user.users u "
                + "LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id WHERE u.user_id IN ("
                + String.join(",", Collections.nCopies(ids, "?")) + ")";
    }

    private static ResultSet member(long id, String nickname, boolean pending) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("user_id")).thenReturn(id);
        when(row.getString("nickname")).thenReturn(nickname);
        when(row.getBoolean("pending")).thenReturn(pending);
        return row;
    }

    private enum FailurePhase { BEGIN, QUERY, MAP, COMMIT }

    private static final class Fixture {
        private final RecordingTransactionManager transactions = new RecordingTransactionManager();
        private final ResultSet row;
        private final RecordingJdbc jdbc;
        private final JdbcContactMembersQuery query;

        private Fixture() throws SQLException {
            row = member(7, "원문 닉네임", false);
            jdbc = new RecordingJdbc(transactions);
            jdbc.rows.add(row);
            var transaction = new TransactionTemplate(transactions);
            transaction.setReadOnly(true);
            transaction.setTimeout(3);
            query = new JdbcContactMembersQuery(jdbc, transaction);
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final RecordingTransactionManager transactions;
        private final List<ResultSet> rows = new ArrayList<>();
        private String sql;
        private Object[] arguments;
        private int queries;
        private RuntimeException failure;

        private RecordingJdbc(RecordingTransactionManager transactions) {
            this.transactions = transactions;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
            assertTrue(transactions.active);
            this.sql = sql;
            this.arguments = arguments;
            queries++;
            if (failure != null) throw failure;
            List<T> result = new ArrayList<>();
            try {
                for (int index = 0; index < rows.size(); index++) result.add(mapper.mapRow(rows.get(index), index));
            } catch (SQLException failure) {
                throw new UncategorizedSQLException("contact batch lookup", sql, failure);
            }
            return result;
        }
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private boolean active;
        private int begins;
        private int commits;
        private int rollbacks;
        private RuntimeException startFailure;
        private RuntimeException completionFailure;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            assertTrue(definition.isReadOnly());
            assertEquals(3, definition.getTimeout());
            begins++;
            if (startFailure != null) throw startFailure;
            active = true;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            active = false;
            commits++;
            if (completionFailure != null) throw completionFailure;
        }

        @Override
        public void rollback(TransactionStatus status) {
            active = false;
            rollbacks++;
        }
    }
}
