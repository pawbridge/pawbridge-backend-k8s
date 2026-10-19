package com.pawbridge.backend.user.internal.profile.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import com.pawbridge.backend.user.internal.profile.application.UserProfile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

class JdbcUserProfileQueryTest {
    private static final LocalDateTime CREATED = LocalDateTime.of(2025, 1, 2, 3, 4, 5, 123456000);
    private static final String EMAIL = " Mixed.Case@example.invalid ";
    private static final String NAME = " 홍길동 ";
    private static final String PROVIDER = "gOoGlE";

    @Test
    void given__stored_profile__when__required__then__exact_seven_column_projection_and_identity_return_after_read_only_commit()
            throws SQLException {
        Fixture fixture = new Fixture("반려친구", "ROLE_ADMIN", CREATED);

        assertEquals(fixture.expected(), fixture.query.require(52L));
        assertEquals("SELECT user_id, email, name, nickname, provider, role, created_at "
                + "FROM pawbridge_user.users WHERE user_id = ?", fixture.jdbc.sql.replaceAll("\\s+", " ").strip());
        assertArrayEquals(new Object[] {52L}, fixture.jdbc.arguments);
        assertEquals(1, fixture.jdbc.queryCount);
        assertEquals(1, fixture.transactions.commits);
        assertEquals(0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__null_creation_timestamp__when__required__then__null_is_preserved_after_transaction_ends() throws SQLException {
        Fixture fixture = new Fixture("반려친구", "ROLE_USER", null);

        assertEquals(fixture.expected(), fixture.query.require(52L));
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @ParameterizedTest
    @MethodSource("storedStrings")
    void given__stored_strings__when__required__then__empty_whitespace_and_database_role_are_preserved(String nickname, String role)
            throws SQLException {
        Fixture fixture = new Fixture(nickname, role, CREATED);

        assertEquals(fixture.expected(), fixture.query.require(52L));
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    private static Stream<Arguments> storedStrings() {
        return Stream.of(Arguments.of("", "ROLE_USER"), Arguments.of(" \t ", "ROLE_ADMIN"),
                Arguments.of("반려친구", "ROLE_SHELTER"));
    }

    @Test
    void given__missing_user__when__required__then__not_found_is_raised_inside_transaction_and_rolls_back() throws SQLException {
        Fixture fixture = new Fixture("반려친구", "ROLE_USER", CREATED);
        when(fixture.rows.next()).thenReturn(false);

        UserNotFoundException failure = assertThrows(UserNotFoundException.class, () -> fixture.query.require(41L));
        assertEquals("존재하지 않는 회원입니다.", failure.getMessage());
        assertEquals(1, fixture.jdbc.queryCount);
        assertEquals(0, fixture.transactions.commits);
        assertEquals(1, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__jdbc_failure__when__required_then_recovered__then__original_failure_rolls_back_and_next_request_commits()
            throws SQLException {
        Fixture fixture = new Fixture("반려친구", "ROLE_USER", CREATED);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("synthetic private SQL failure");
        fixture.jdbc.failure = failure;

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> fixture.query.require(52L)));
        assertEquals(1, fixture.jdbc.queryCount);
        assertEquals(1, fixture.transactions.rollbacks);
        assertEquals(0, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
        fixture.jdbc.failure = null;

        assertEquals(fixture.expected(), fixture.query.require(52L));
        assertEquals(2, fixture.jdbc.queryCount);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__transaction_start_failure__when__required_then_recovered__then__no_query_precedes_failure_and_next_request_commits()
            throws SQLException {
        Fixture fixture = new Fixture("반려친구", "ROLE_USER", CREATED);
        CannotCreateTransactionException failure = new CannotCreateTransactionException("synthetic private start failure");
        fixture.transactions.startFailure = failure;

        assertSame(failure, assertThrows(CannotCreateTransactionException.class, () -> fixture.query.require(52L)));
        assertEquals(0, fixture.jdbc.queryCount);
        assertEquals(0, fixture.transactions.commits);
        assertEquals(0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
        fixture.transactions.startFailure = null;

        assertEquals(fixture.expected(), fixture.query.require(52L));
        assertEquals(1, fixture.jdbc.queryCount);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__transaction_completion_failure__when__required_then_recovered__then__no_profile_is_returned_and_next_request_commits()
            throws SQLException {
        Fixture fixture = new Fixture("반려친구", "ROLE_USER", CREATED);
        TransactionSystemException failure = new TransactionSystemException("synthetic private completion failure");
        fixture.transactions.completionFailure = failure;

        assertSame(failure, assertThrows(TransactionSystemException.class, () -> fixture.query.require(52L)));
        assertEquals(1, fixture.jdbc.queryCount);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
        fixture.transactions.completionFailure = null;

        assertEquals(fixture.expected(), fixture.query.require(52L));
        assertEquals(2, fixture.jdbc.queryCount);
        assertEquals(2, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    private static final class Fixture {
        private final RecordingTransactionManager transactions = new RecordingTransactionManager();
        private final ResultSet rows = mock(ResultSet.class);
        private final StubJdbcTemplate jdbc = new StubJdbcTemplate(rows, transactions);
        private final JdbcUserProfileQuery query;
        private final String nickname;
        private final String role;
        private final LocalDateTime createdAt;

        private Fixture(String nickname, String role, LocalDateTime createdAt) throws SQLException {
            this.nickname = nickname;
            this.role = role;
            this.createdAt = createdAt;
            when(rows.next()).thenReturn(true);
            when(rows.getLong("user_id")).thenReturn(52L);
            when(rows.getString("email")).thenReturn(EMAIL);
            when(rows.getString("name")).thenReturn(NAME);
            when(rows.getString("nickname")).thenReturn(nickname);
            when(rows.getString("provider")).thenReturn(PROVIDER);
            when(rows.getString("role")).thenReturn(role);
            when(rows.getTimestamp(eq("created_at"), any(Calendar.class))).thenAnswer(invocation -> {
                assertTrue(transactions.active);
                assertEquals("UTC", invocation.getArgument(1, Calendar.class).getTimeZone().getID());
                return createdAt == null ? null : Timestamp.valueOf(createdAt);
            });
            TransactionTemplate readOnlyTransaction = new TransactionTemplate(transactions);
            readOnlyTransaction.setReadOnly(true);
            readOnlyTransaction.setTimeout(3);
            query = new JdbcUserProfileQuery(jdbc, readOnlyTransaction);
        }

        private UserProfile expected() {
            return new UserProfile(52L, EMAIL, NAME, nickname, PROVIDER, role, createdAt);
        }
    }

    private static final class StubJdbcTemplate extends JdbcTemplate {
        private final ResultSet rows;
        private final RecordingTransactionManager transactions;
        private RuntimeException failure;
        private String sql;
        private Object[] arguments;
        private int queryCount;

        private StubJdbcTemplate(ResultSet rows, RecordingTransactionManager transactions) {
            this.rows = rows;
            this.transactions = transactions;
        }

        @Override
        public <T> T query(String sql, ResultSetExtractor<T> extractor, Object... arguments) {
            assertTrue(transactions.active, "The profile SELECT and missing-user decision belong to the read transaction.");
            this.sql = sql;
            this.arguments = arguments;
            queryCount++;
            if (failure != null) throw failure;
            try {
                return extractor.extractData(rows);
            } catch (SQLException failure) {
                throw new UncategorizedSQLException("profile lookup", sql, failure);
            }
        }
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private boolean active;
        private int commits;
        private int rollbacks;
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
            rollbacks++;
        }
    }
}
