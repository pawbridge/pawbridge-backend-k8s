package com.pawbridge.backend.user.internal.password.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.user.internal.password.application.StoredCredentials;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;

class JdbcUserPasswordStoreTest {
    private static final LocalDateTime UPDATED = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123456000);

    @Test
    void given__present_then_missing_user__when__looking_up__then__provider_and_raw_hash_are_projected_without_other_columns()
            throws SQLException {
        var jdbc = new RecordingJdbc();
        when(jdbc.rows.next()).thenReturn(true, false);
        when(jdbc.rows.getString("provider")).thenReturn("LOCAL");
        when(jdbc.rows.getString("password")).thenReturn("$2a$10$synthetic");
        var store = new JdbcUserPasswordStore(jdbc);

        assertEquals(Optional.of(new StoredCredentials("LOCAL", "$2a$10$synthetic")), store.findCredentials(52L));
        assertEquals("SELECT user_id, provider, password FROM pawbridge_user.users WHERE user_id = ?", normalized(jdbc.querySql));
        assertArrayEquals(new Object[] {52L}, jdbc.arguments);
        assertEquals(Optional.empty(), store.findCredentials(53L));
    }

    @Test
    void given__social_member_without_password__when__looking_up__then__null_hash_is_preserved() throws SQLException {
        var jdbc = new RecordingJdbc();
        when(jdbc.rows.next()).thenReturn(true);
        when(jdbc.rows.getString("provider")).thenReturn("GOOGLE");
        var store = new JdbcUserPasswordStore(jdbc);

        assertEquals(Optional.of(new StoredCredentials("GOOGLE", null)), store.findCredentials(52L));
    }

    @Test
    void given__encoded_password_and_auditing_time__when__updating__then__only_password_and_updated_at_are_bound_with_utc_calendar()
            throws SQLException {
        var jdbc = new RecordingJdbc();
        var store = new JdbcUserPasswordStore(jdbc, () -> UPDATED);

        store.updatePassword(52L, "$2a$10$synthetic.new");

        assertEquals("UPDATE pawbridge_user.users SET password = ?, updated_at = ? WHERE user_id = ?", normalized(jdbc.updateSql));
        verify(jdbc.statement).setString(1, "$2a$10$synthetic.new");
        var calendar = ArgumentCaptor.forClass(Calendar.class);
        verify(jdbc.statement).setTimestamp(eq(2), eq(Timestamp.valueOf(UPDATED)), calendar.capture());
        assertEquals("UTC", calendar.getValue().getTimeZone().getID());
        verify(jdbc.statement).setLong(3, 52L);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void given__unexpected_update_count__when__updating__then__unexpected_failure_is_not_success(int count) throws SQLException {
        var jdbc = new RecordingJdbc();
        jdbc.changed = count;
        var store = new JdbcUserPasswordStore(jdbc, () -> UPDATED);

        assertThrows(IllegalStateException.class, () -> store.updatePassword(52L, "$2a$10$synthetic.new"));
    }

    @Test
    void given__parameter_binding_failure__when__updating__then__statement_is_closed_before_jdbc_propagates_failure() throws SQLException {
        var jdbc = new RecordingJdbc();
        var failure = new SQLException("synthetic private binding failure");
        doThrow(failure).when(jdbc.statement).setTimestamp(eq(2), any(Timestamp.class), any(Calendar.class));
        var store = new JdbcUserPasswordStore(jdbc, () -> UPDATED);

        var thrown = assertThrows(UncategorizedSQLException.class, () -> store.updatePassword(52L, "$2a$10$synthetic.new"));

        assertSame(failure, thrown.getSQLException());
        verify(jdbc.statement).close();
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final ResultSet rows = mock(ResultSet.class);
        private final Connection connection = mock(Connection.class);
        private final PreparedStatement statement = mock(PreparedStatement.class);
        private String querySql;
        private String updateSql;
        private Object[] arguments;
        private int changed = 1;

        private RecordingJdbc() throws SQLException {
            when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
                updateSql = invocation.getArgument(0, String.class);
                return statement;
            });
        }

        @Override
        public <T> T query(String sql, ResultSetExtractor<T> extractor, Object... args) {
            querySql = sql;
            arguments = args;
            try {
                return extractor.extractData(rows);
            } catch (SQLException failure) {
                throw new UncategorizedSQLException("password lookup", sql, failure);
            }
        }

        @Override
        public int update(PreparedStatementCreator creator) {
            try {
                creator.createPreparedStatement(connection);
                return changed;
            } catch (SQLException failure) {
                throw new UncategorizedSQLException("password update", updateSql, failure);
            }
        }
    }
}
