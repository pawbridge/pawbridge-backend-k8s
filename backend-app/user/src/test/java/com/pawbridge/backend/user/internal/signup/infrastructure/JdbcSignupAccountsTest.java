package com.pawbridge.backend.user.internal.signup.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.user.internal.signup.application.SignedUp;
import com.pawbridge.backend.user.internal.signup.application.SignupRole;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;

class JdbcSignupAccountsTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123456000);

    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final ArrayDeque<String> nicknames = new ArrayDeque<>(List.of("첫번째닉네임", "두번째닉네임"));
    private final JdbcSignupAccounts accounts = new JdbcSignupAccounts(jdbc, nicknames::removeFirst, () -> NOW);

    @Test
    void given__email__when__checking_duplicates__then__case_insensitive_local_provider_query() {
        when(jdbc.queryForObject(any(String.class), eq(Boolean.class), eq("a@b.c"), eq("LOCAL"))).thenReturn(true, false, null);

        assertTrue(accounts.existsLocalEmail("a@b.c"));
        assertFalse(accounts.existsLocalEmail("a@b.c"));
        assertFalse(accounts.existsLocalEmail("a@b.c"));
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).queryForObject(sql.capture(), eq(Boolean.class), eq("a@b.c"), eq("LOCAL"));
        assertEquals("SELECT (count(*) > 0) FROM pawbridge_user.users WHERE lower(email) = lower(?) AND lower(provider) = lower(?)",
                sql.getValue().replaceAll("\\s+", " ").trim());
    }

    @Test
    void given__new_account__when__creating__then__insert_binds_local_user_columns_with_utc_timestamps_and_returns_key() throws Exception {
        var statement = capture(7L);

        SignedUp saved = accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null);

        assertEquals(new SignedUp(7, "a@b.c", "Kim", "첫번째닉네임"), saved);
        verify(statement).setString(1, "a@b.c");
        verify(statement).setString(2, "Kim");
        verify(statement).setString(3, "첫번째닉네임");
        verify(statement).setString(4, "ENC");
        verify(statement).setString(5, "ROLE_USER");
        verify(statement).setString(6, null);
        verify(statement).setString(7, "LOCAL");
        verify(statement).setString(8, null);
        var calendar = ArgumentCaptor.forClass(Calendar.class);
        verify(statement).setTimestamp(eq(9), eq(Timestamp.valueOf(NOW)), calendar.capture());
        verify(statement).setTimestamp(eq(10), eq(Timestamp.valueOf(NOW)), any(Calendar.class));
        assertEquals(TimeZone.getTimeZone("UTC"), calendar.getValue().getTimeZone());
    }

    @Test
    void given__nickname_conflict__when__creating__then__regenerates_once_in_the_same_unit_of_work() {
        var attempts = new int[1];
        doAnswer(invocation -> {
            if (attempts[0]++ == 0) throw new DataIntegrityViolationException("duplicate nickname");
            ((KeyHolder) invocation.getArgument(1)).getKeyList().add(Map.of("user_id", 9L));
            return 1;
        }).when(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));

        SignedUp saved = accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null);

        assertEquals(new SignedUp(9, "a@b.c", "Kim", "두번째닉네임"), saved);
        assertEquals(2, attempts[0]);
    }

    @Test
    void given__second_conflict__when__creating__then__failure_propagates_after_one_retry() {
        doAnswer(invocation -> { throw new DataIntegrityViolationException("duplicate"); })
                .when(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));

        assertThrows(DataIntegrityViolationException.class,
                () -> accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null));

        verify(jdbc, times(2)).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    @Test
    void given__rollback_charset_check_failure__when__creating__then__rethrown_without_retry() {
        var postgres = mock(PSQLException.class);
        var server = mock(ServerErrorMessage.class);
        when(postgres.getSQLState()).thenReturn("23514");
        when(postgres.getServerErrorMessage()).thenReturn(server);
        when(server.getSchema()).thenReturn("pawbridge_user");
        when(server.getConstraint()).thenReturn("ck_rollback_charset_users_name");
        var failure = new DataIntegrityViolationException("check", postgres);
        doAnswer(invocation -> { throw failure; }).when(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));

        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null)));

        verify(jdbc, times(1)).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        assertTrue(RollbackCharsetViolation.matches(failure));
        assertFalse(RollbackCharsetViolation.matches(new DataIntegrityViolationException("other")));
    }

    @Test
    void given__no_generated_key__when__creating__then__fail_instead_of_returning_a_partial_result() {
        when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenReturn(1);

        assertThrows(IllegalStateException.class,
                () -> accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null));
    }

    private PreparedStatement capture(long key) throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(any(String.class), any(String[].class))).thenReturn(statement);
        doAnswer(invocation -> {
            ((PreparedStatementCreator) invocation.getArgument(0)).createPreparedStatement(connection);
            ((KeyHolder) invocation.getArgument(1)).getKeyList().add(Map.of("user_id", key));
            return 1;
        }).when(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        return statement;
    }
}
