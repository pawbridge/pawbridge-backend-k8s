package com.pawbridge.backend.user.internal.passwordreset.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.List;
import java.util.OptionalLong;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;

class JdbcPasswordResetAccountsTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123456000);

    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final JdbcPasswordResetAccounts accounts = new JdbcPasswordResetAccounts(jdbc, () -> NOW);

    @SuppressWarnings("unchecked")
    @Test
    void given__email__when__looking_up__then__case_insensitive_local_query_projects_only_the_id() {
        when(jdbc.query(any(String.class), any(RowMapper.class), eq("a@b.c"), eq("LOCAL")))
                .thenReturn(List.of(7L), List.of(), List.of(7L, 8L));

        assertEquals(OptionalLong.of(7), accounts.findLocalUserId("a@b.c"));
        assertEquals(OptionalLong.empty(), accounts.findLocalUserId("a@b.c"));
        assertThrows(IncorrectResultSizeDataAccessException.class, () -> accounts.findLocalUserId("a@b.c"));
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(3)).query(sql.capture(), any(RowMapper.class), eq("a@b.c"), eq("LOCAL"));
        assertEquals("SELECT user_id FROM pawbridge_user.users WHERE lower(email) = lower(?) AND lower(provider) = lower(?)",
                sql.getValue().replaceAll("\\s+", " ").trim());
    }

    @Test
    void given__user__when__updating__then__only_password_and_updated_at_are_written_with_utc_binding() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var sql = ArgumentCaptor.forClass(String.class);
        when(connection.prepareStatement(sql.capture())).thenReturn(statement);
        doAnswer(invocation -> {
            ((PreparedStatementCreator) invocation.getArgument(0)).createPreparedStatement(connection);
            return 1;
        }).when(jdbc).update(any(PreparedStatementCreator.class));

        accounts.updatePassword(7, "ENC");

        assertEquals("UPDATE pawbridge_user.users SET password = ?, updated_at = ? WHERE user_id = ?",
                sql.getValue().replaceAll("\\s+", " ").trim());
        verify(statement).setString(1, "ENC");
        var calendar = ArgumentCaptor.forClass(Calendar.class);
        verify(statement).setTimestamp(eq(2), eq(Timestamp.valueOf(NOW)), calendar.capture());
        assertEquals(TimeZone.getTimeZone("UTC"), calendar.getValue().getTimeZone());
        verify(statement).setLong(3, 7);
    }

    @Test
    void given__no_row_changed__when__updating__then__fail() {
        when(jdbc.update(any(PreparedStatementCreator.class))).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> accounts.updatePassword(7, "ENC"));
    }
}
