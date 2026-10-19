package com.pawbridge.backend.user.internal.contact.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcContactMemberQueryTest {
    private static final long USER_ID = 99L;
    private static final String NICKNAME = "  원문\n닉네임  ";

    @ParameterizedTest
    @EnumSource(MemberState.class)
    void given__active_missing_or_pending_user__when__reading__then__return_exact_contact_projection_after_read_only_completion(
            MemberState state) throws SQLException {
        var fixture = new Fixture();
        when(fixture.rows.next()).thenReturn(state != MemberState.MISSING);
        when(fixture.rows.getBoolean("pending")).thenReturn(state == MemberState.PENDING);

        ContactMember found = fixture.query.get(USER_ID);

        assertEquals(new ContactMember(USER_ID, state == MemberState.ACTIVE ? NICKNAME : "탈퇴한 회원",
                state == MemberState.ACTIVE, state == MemberState.PENDING), found);
        assertEquals("SELECT u.user_id, u.nickname, d.user_id IS NOT NULL AS pending FROM pawbridge_user.users u "
                + "LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id WHERE u.user_id = ?",
                fixture.jdbc.sql.replaceAll("\\s+", " ").strip());
        assertArrayEquals(new Object[] {USER_ID}, fixture.jdbc.arguments);
        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
        verify(fixture.rows).next();
        if (state != MemberState.MISSING) {
            verify(fixture.rows).getBoolean("pending");
            verify(fixture.rows).getLong("user_id");
        }
        if (state == MemberState.ACTIVE) verify(fixture.rows).getString("nickname");
        verifyNoMoreInteractions(fixture.rows);
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__user_query_or_transaction_runtime_then_recovery__when__reading__then__preserve_failure_as_typed_unavailable_without_empty_fallback(
            FailurePhase phase) throws SQLException {
        var fixture = new Fixture();
        RuntimeException failure = switch (phase) {
            case BEGIN -> new CannotCreateTransactionException("synthetic-private-begin");
            case QUERY -> new DataAccessResourceFailureException("synthetic-private-sql");
            case MAP -> new IllegalStateException("synthetic-private-mapping");
            case COMMIT -> new TransactionSystemException("synthetic-private-commit");
        };
        switch (phase) {
            case BEGIN -> fixture.transactions.startFailure = failure;
            case QUERY -> fixture.jdbc.failure = failure;
            case MAP -> when(fixture.rows.getBoolean("pending")).thenThrow(failure);
            case COMMIT -> fixture.transactions.completionFailure = failure;
        }

        var unavailable = assertThrows(ContactMemberUnavailableException.class, () -> fixture.query.get(USER_ID));

        assertSame(failure, unavailable.getCause());
        assertEquals("Contact member lookup is unavailable.", unavailable.getMessage());
        assertEquals(phase == FailurePhase.BEGIN ? 0 : 1, fixture.jdbc.queries);
        assertFalse(fixture.transactions.active);
        fixture.transactions.startFailure = null;
        fixture.transactions.completionFailure = null;
        fixture.jdbc.failure = null;
        doReturn(false).when(fixture.rows).getBoolean("pending");

        assertEquals(new ContactMember(USER_ID, NICKNAME, true, false), fixture.query.get(USER_ID));
        assertFalse(fixture.transactions.active);
        assertEquals(phase == FailurePhase.BEGIN ? 1 : 2, fixture.jdbc.queries);
    }

    private enum MemberState { ACTIVE, MISSING, PENDING }
    private enum FailurePhase { BEGIN, QUERY, MAP, COMMIT }

    private static final class Fixture {
        private final RecordingTransactionManager transactions = new RecordingTransactionManager();
        private final ResultSet rows = mock(ResultSet.class);
        private final RecordingJdbc jdbc = new RecordingJdbc(rows, transactions);
        private final JdbcContactMemberQuery query;

        private Fixture() throws SQLException {
            when(rows.next()).thenReturn(true);
            when(rows.getLong("user_id")).thenReturn(USER_ID);
            when(rows.getString("nickname")).thenReturn(NICKNAME);
            var transaction = new TransactionTemplate(transactions);
            transaction.setReadOnly(true);
            transaction.setTimeout(3);
            query = new JdbcContactMemberQuery(jdbc, transaction);
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final ResultSet rows;
        private final RecordingTransactionManager transactions;
        private String sql;
        private Object[] arguments;
        private int queries;
        private RuntimeException failure;

        private RecordingJdbc(ResultSet rows, RecordingTransactionManager transactions) {
            this.rows = rows;
            this.transactions = transactions;
        }

        @Override
        public <T> T query(String sql, ResultSetExtractor<T> extractor, Object... arguments) {
            assertTrue(transactions.active);
            this.sql = sql;
            this.arguments = arguments;
            queries++;
            if (failure != null) throw failure;
            try {
                return extractor.extractData(rows);
            } catch (SQLException failure) {
                throw new UncategorizedSQLException("contact lookup", sql, failure);
            }
        }
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private boolean active;
        private int commits;
        private RuntimeException startFailure;
        private RuntimeException completionFailure;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            assertTrue(definition.isReadOnly());
            assertEquals(3, definition.getTimeout());
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
        }
    }
}
