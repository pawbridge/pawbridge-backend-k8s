package com.pawbridge.backend.user.internal.nickname.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pawbridge.backend.user.api.UserNicknameUnavailableException;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

class JdbcUserNicknameQueryTest {
    @Test
    void given__missing_user__when__nickname_is_queried__then__empty_is_returned_after_transaction_ends()
            throws SQLException {
        try (Fixture fixture = new Fixture()) {
            Optional<String> nickname = fixture.query.findNickname(41L);

            assertEquals(Optional.empty(), nickname);
            assertFalse(fixture.transactions.active);
            assertEquals(1, fixture.transactions.commits);
            assertEquals("SELECT nickname FROM pawbridge_user.users WHERE user_id = ?", fixture.jdbc.sql);
            assertArrayEquals(new Object[] {41L}, fixture.jdbc.arguments);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"산책하는강아지", "", " "})
    void given__stored_nickname__when__nickname_is_queried__then__value_is_preserved_after_transaction_ends(
            String storedNickname) throws SQLException {
        try (Fixture fixture = new Fixture(storedNickname)) {
            Optional<String> nickname = fixture.query.findNickname(52L);

            assertEquals(Optional.of(storedNickname), nickname);
            assertFalse(fixture.transactions.active);
            assertEquals(1, fixture.transactions.commits);
        }
    }

    @Test
    void given__jdbc_failure__when__nickname_is_queried__then__unavailable_is_thrown_after_rollback()
            throws SQLException {
        try (Fixture fixture = new Fixture()) {
            var failure = new DataAccessResourceFailureException("sensitive connection details");
            fixture.jdbc.failure = failure;

            UserNicknameUnavailableException exception = assertThrows(
                    UserNicknameUnavailableException.class, () -> fixture.query.findNickname(41L));

            assertSame(failure, exception.getCause());
            assertEquals("User nickname lookup is unavailable.", exception.getMessage());
            assertFalse(fixture.transactions.active);
            assertEquals(1, fixture.transactions.rollbacks);
        }
    }

    @Test
    void given__transaction_start_failure__when__nickname_is_queried__then__unavailable_is_thrown_without_query()
            throws SQLException {
        try (Fixture fixture = new Fixture()) {
            var failure = new CannotCreateTransactionException("sensitive transaction details");
            fixture.transactions.startFailure = failure;

            UserNicknameUnavailableException exception = assertThrows(
                    UserNicknameUnavailableException.class, () -> fixture.query.findNickname(41L));

            assertSame(failure, exception.getCause());
            assertEquals("User nickname lookup is unavailable.", exception.getMessage());
            assertEquals(0, fixture.jdbc.queryCount);
            assertFalse(fixture.transactions.active);
        }
    }

    @Test
    void given__transaction_completion_failure__when__nickname_is_queried__then__unavailable_is_thrown()
            throws SQLException {
        try (Fixture fixture = new Fixture("산책하는강아지")) {
            var failure = new TransactionSystemException("sensitive completion details");
            fixture.transactions.completionFailure = failure;

            UserNicknameUnavailableException exception = assertThrows(
                    UserNicknameUnavailableException.class, () -> fixture.query.findNickname(41L));

            assertSame(failure, exception.getCause());
            assertEquals("User nickname lookup is unavailable.", exception.getMessage());
            assertEquals(1, fixture.jdbc.queryCount);
            assertFalse(fixture.transactions.active);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final RecordingTransactionManager transactions = new RecordingTransactionManager();
        private final CachedRowSet rows;
        private final StubJdbcTemplate jdbc;
        private final JdbcUserNicknameQuery query;

        private Fixture(String... nicknames) throws SQLException {
            var metadata = new RowSetMetaDataImpl();
            metadata.setColumnCount(1);
            metadata.setColumnName(1, "nickname");
            metadata.setColumnType(1, Types.VARCHAR);
            metadata.setNullable(1, ResultSetMetaData.columnNoNulls);
            rows = RowSetProvider.newFactory().createCachedRowSet();
            rows.setMetaData(metadata);
            for (String nickname : nicknames) {
                rows.moveToInsertRow();
                rows.updateString(1, nickname);
                rows.insertRow();
            }
            rows.moveToCurrentRow();
            rows.beforeFirst();
            jdbc = new StubJdbcTemplate(rows, transactions);
            var readOnlyTransaction = new TransactionTemplate(transactions);
            readOnlyTransaction.setReadOnly(true);
            query = new JdbcUserNicknameQuery(jdbc, readOnlyTransaction);
        }

        @Override
        public void close() throws SQLException {
            rows.close();
        }
    }

    private static final class StubJdbcTemplate extends JdbcTemplate {
        private final CachedRowSet rows;
        private final RecordingTransactionManager transactions;
        private RuntimeException failure;
        private String sql;
        private Object[] arguments;
        private int queryCount;

        private StubJdbcTemplate(CachedRowSet rows, RecordingTransactionManager transactions) {
            this.rows = rows;
            this.transactions = transactions;
        }

        @Override
        public <T> T query(String sql, ResultSetExtractor<T> resultSetExtractor, Object... arguments) {
            assertTrue(transactions.active, "The nickname SELECT must run inside its transaction.");
            this.sql = sql;
            this.arguments = arguments;
            queryCount++;
            if (failure != null) {
                throw failure;
            }
            try {
                return resultSetExtractor.extractData(rows);
            } catch (SQLException exception) {
                throw new UncategorizedSQLException("nickname lookup", sql, exception);
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
            if (startFailure != null) {
                throw startFailure;
            }
            active = true;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            active = false;
            commits++;
            if (completionFailure != null) {
                throw completionFailure;
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
            active = false;
            rollbacks++;
        }
    }
}
