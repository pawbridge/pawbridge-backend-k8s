package com.pawbridge.backend.user.internal.nickname.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

class JdbcUserNicknameStoreTest {
    private static final LocalDateTime UPDATED = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123456000);

    @Test
    void given__present_then_missing_user__when__looking_up__then__two_column_projection_preserves_raw_nickname_and_absence()
            throws SQLException {
        var jdbc = new RecordingJdbc();
        when(jdbc.rows.next()).thenReturn(true, false);
        when(jdbc.rows.getString("nickname")).thenReturn("MiXeD닉네임");
        var store = new JdbcUserNicknameStore(jdbc);

        assertEquals(Optional.of("MiXeD닉네임"), store.findNickname(52L));
        assertEquals("SELECT user_id, nickname FROM pawbridge_user.users WHERE user_id = ?", normalized(jdbc.querySql));
        assertArrayEquals(new Object[] {52L}, jdbc.arguments);
        assertEquals(Optional.empty(), store.findNickname(53L));
    }

    @Test
    void given__mixed_case_input__when__checking_duplicate__then__database_lower_query_receives_original_string_without_self_exclusion()
            throws SQLException {
        var jdbc = new RecordingJdbc();
        var store = new JdbcUserNicknameStore(jdbc);

        assertFalse(store.existsByNickname("MiXeD닉네임"));

        assertEquals("SELECT (count(*) > 0) FROM pawbridge_user.users WHERE lower(nickname) = lower(?)", normalized(jdbc.querySql));
        assertArrayEquals(new Object[] {"MiXeD닉네임"}, jdbc.arguments);
    }

    @Test
    void given__available_nickname_and_auditing_time__when__updating__then__only_nickname_and_updated_at_are_bound_with_utc_calendar()
            throws SQLException {
        var jdbc = new RecordingJdbc();
        var store = new JdbcUserNicknameStore(jdbc, () -> UPDATED);

        store.updateNickname(52L, "새Nick9");

        assertEquals("UPDATE pawbridge_user.users SET nickname = ?, updated_at = ? WHERE user_id = ?", normalized(jdbc.updateSql));
        verify(jdbc.statement).setString(1, "새Nick9");
        var calendar = ArgumentCaptor.forClass(Calendar.class);
        verify(jdbc.statement).setTimestamp(eq(2), eq(Timestamp.valueOf(UPDATED)), calendar.capture());
        assertEquals("UTC", calendar.getValue().getTimeZone().getID());
        verify(jdbc.statement).setLong(3, 52L);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void given__unexpected_update_count__when__updating__then__unexpected_failure_is_not_missing_user_or_success(int count)
            throws SQLException {
        var jdbc = new RecordingJdbc();
        jdbc.changed = count;
        var store = new JdbcUserNicknameStore(jdbc, () -> UPDATED);

        assertThrows(IllegalStateException.class, () -> store.updateNickname(52L, "새Nick9"));
    }

    @Test
    void given__parameter_binding_failure__when__updating__then__statement_is_closed_before_jdbc_propagates_failure() throws SQLException {
        var jdbc = new RecordingJdbc();
        var failure = new SQLException("synthetic private binding failure");
        doThrow(failure).when(jdbc.statement).setTimestamp(eq(2), any(Timestamp.class), any(Calendar.class));
        var store = new JdbcUserNicknameStore(jdbc, () -> UPDATED);

        var thrown = assertThrows(UncategorizedSQLException.class, () -> store.updateNickname(52L, "새Nick9"));

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
                throw new UncategorizedSQLException("nickname lookup", sql, failure);
            }
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            querySql = sql;
            arguments = args;
            return type.cast(Boolean.FALSE);
        }

        @Override
        public int update(PreparedStatementCreator creator) {
            try {
                creator.createPreparedStatement(connection);
                return changed;
            } catch (SQLException failure) {
                throw new UncategorizedSQLException("nickname update", updateSql, failure);
            }
        }
    }
}
