package com.pawbridge.backend.user.internal.adminupdate.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate.Changes;
import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate.Role;
import com.pawbridge.backend.user.internal.adminupdate.application.ShelterApprovalRequiredException;
import com.pawbridge.backend.user.internal.nickname.application.NicknameDuplicateException;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

class JdbcAdminUserUpdateTest {
    private static final LocalDateTime MODIFIED = LocalDateTime.of(2030, 2, 3, 4, 5, 6, 123456000);

    @ParameterizedTest @ValueSource(longs = {-1, 0, 8})
    void given__missing_target_including_nonpositive_id__when__locking__then__preserve_missing_user_without_a_new_positive_guard(long id) {
        var f = new Fixture(); f.jdbc.present = false;
        assertThrows(UserNotFoundException.class, () -> f.query.update(id, new Changes("Duplicate", Role.ROLE_SHELTER, "B")));
        assertEquals(List.of("lock"), f.jdbc.events); assertArrayEquals(new Object[]{id}, f.jdbc.lockArguments);
        assertTrue(f.jdbc.lockSql.endsWith("WHERE user_id = ? FOR UPDATE"));
        assertEquals(1, f.tx.rollbacks); assertEquals(0, f.samples); assertFalse(f.tx.active);
    }

    @ParameterizedTest @MethodSource("approvalRequests")
    void given__approval_required_before_a_duplicate_nickname__when__updating_locked_user__then__reject_before_duplicate_or_write(Role current, String care, Changes request) {
        var f = new Fixture(); f.jdbc.role = current; f.jdbc.care = care; f.jdbc.duplicate = true;
        assertThrows(ShelterApprovalRequiredException.class, () -> f.query.update(8, request));
        assertEquals(List.of("lock"), f.jdbc.events); assertEquals(0, f.samples); assertEquals(1, f.tx.rollbacks);
    }
    static Stream<Arguments> approvalRequests() {
        return Stream.of(Arguments.of(Role.ROLE_USER, null, new Changes("Duplicate", Role.ROLE_SHELTER, null)),
                Arguments.of(Role.ROLE_SHELTER, "A", new Changes("Duplicate", null, "B")),
                Arguments.of(Role.ROLE_SHELTER, null, new Changes(null, null, "A")));
    }

    @Test
    void given__case_only_nickname_and_existing_self_match__when__updating__then__keep_original_duplicate_query_and_409_exception() {
        var f = new Fixture(); f.jdbc.duplicate = true;
        assertThrows(NicknameDuplicateException.class, () -> f.query.update(8, new Changes("ALPHA", null, null)));
        assertEquals(List.of("lock", "duplicate"), f.jdbc.events);
        assertEquals("SELECT (count(*) > 0) FROM pawbridge_user.users WHERE lower(nickname) = lower(?)", f.jdbc.duplicateSql);
        assertArrayEquals(new Object[]{"ALPHA"}, f.jdbc.duplicateArguments);
        assertEquals(0, f.samples); assertEquals(1, f.tx.rollbacks);
    }

    @ParameterizedTest @MethodSource("noChanges")
    void given__no_dirty_property__when__saving_equivalent_values__then__commit_without_update_or_auditing(Changes request) {
        var f = new Fixture(); f.jdbc.care = "A";
        f.query.update(8, request);
        assertEquals(List.of("lock"), f.jdbc.events); assertEquals(0, f.samples);
        assertEquals(1, f.tx.commits); assertEquals(0, f.tx.rollbacks); assertFalse(f.tx.active);
    }
    static Stream<Changes> noChanges() {
        return Stream.of(new Changes(null, null, null), new Changes("Alpha", Role.ROLE_USER, "A"),
                new Changes(null, null, "  "), new Changes("", null, null));
    }

    @ParameterizedTest @MethodSource("validChanges")
    void given__allowed_dirty_fields__when__updating__then__bind_only_mutable_fields_and_one_utc_audit_time_inside_the_lock_transaction(Role current, String currentCare, Changes request, String nickname, Role nextRole) throws Exception {
        var f = new Fixture(); f.jdbc.role = current; f.jdbc.care = currentCare;
        f.query.update(8, request);
        assertEquals(request.nickname() != null && !request.nickname().equals("Alpha")
                ? List.of("lock", "duplicate", "update") : List.of("lock", "update"), f.jdbc.events);
        assertEquals("UPDATE pawbridge_user.users SET nickname = ?, role = ?, care_reg_no = ?, updated_at = ? WHERE user_id = ?", f.jdbc.updateSql);
        verify(f.jdbc.statement).setString(1, nickname); verify(f.jdbc.statement).setString(2, nextRole.name());
        verify(f.jdbc.statement).setString(3, currentCare);
        var zone = ArgumentCaptor.forClass(Calendar.class);
        verify(f.jdbc.statement).setTimestamp(eq(4), eq(Timestamp.valueOf(MODIFIED)), zone.capture());
        assertEquals("UTC", zone.getValue().getTimeZone().getID()); verify(f.jdbc.statement).setLong(5, 8);
        assertEquals(1, f.samples); assertEquals(1, f.tx.starts); assertEquals(1, f.tx.commits); assertFalse(f.tx.active);
    }
    static Stream<Arguments> validChanges() {
        return Stream.of(Arguments.of(Role.ROLE_USER, null, new Changes("123", null, null), "123", Role.ROLE_USER),
                Arguments.of(Role.ROLE_USER, null, new Changes(null, Role.ROLE_ADMIN, null), "Alpha", Role.ROLE_ADMIN),
                Arguments.of(Role.ROLE_SHELTER, "A", new Changes(null, Role.ROLE_USER, "A"), "Alpha", Role.ROLE_USER),
                Arguments.of(Role.ROLE_SHELTER, "A", new Changes("Beta", Role.ROLE_SHELTER, "A"), "Beta", Role.ROLE_SHELTER));
    }

    @ParameterizedTest @ValueSource(strings = {"begin", "update", "commit"})
    void given__begin_unique_race_or_commit_failure__when__updating__then__propagate_without_duplicate_reclassification_or_retry(String phase) {
        var f = new Fixture(); RuntimeException failure = phase.equals("update")
                ? new DataIntegrityViolationException("synthetic unique race") : new TransactionSystemException("synthetic boundary");
        if (phase.equals("begin")) f.tx.beginFailure = failure;
        else if (phase.equals("commit")) f.tx.commitFailure = failure;
        else f.jdbc.updateFailure = failure;
        assertSame(failure, assertThrows(RuntimeException.class, () -> f.query.update(8, new Changes("Beta", null, null))));
        assertEquals(1, f.tx.starts); assertEquals(phase.equals("update") ? 1 : 0, f.tx.rollbacks); assertFalse(f.tx.active);
    }

    @ParameterizedTest @ValueSource(ints = {0, 2})
    void given__unexpected_write_count__when__updating_locked_user__then__rollback_instead_of_reporting_success(int changed) {
        var f = new Fixture(); f.jdbc.changed = changed;
        assertThrows(IllegalStateException.class, () -> f.query.update(8, new Changes("Beta", null, null)));
        assertEquals(1, f.tx.rollbacks); assertEquals(0, f.tx.commits);
    }

    @Test
    void given__prepared_statement_binding_failure__when__updating__then__close_the_statement_and_propagate_sql_failure() throws Exception {
        var f = new Fixture(); SQLException failure = new SQLException("synthetic bind");
        doThrow(failure).when(f.jdbc.statement).setString(2, "ROLE_USER");
        var thrown = assertThrows(UncategorizedSQLException.class, () -> f.query.update(8, new Changes("Beta", null, null)));
        assertSame(failure, thrown.getSQLException()); verify(f.jdbc.statement).close(); assertEquals(1, f.tx.rollbacks);
    }

    private static final class Fixture {
        final RecordingTransactions tx = new RecordingTransactions();
        final RecordingJdbc jdbc = new RecordingJdbc(tx);
        int samples;
        final JdbcAdminUserUpdate query;
        Fixture() {
            var transaction = new TransactionTemplate(tx); transaction.setTimeout(15);
            query = new JdbcAdminUserUpdate(jdbc, transaction, () -> { assertTrue(tx.active); samples++; return MODIFIED; });
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        final RecordingTransactions tx;
        final ResultSet rows = mock(ResultSet.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        final List<String> events = new ArrayList<>();
        boolean present = true, duplicate;
        Role role = Role.ROLE_USER; String care, lockSql, duplicateSql, updateSql;
        Object[] lockArguments, duplicateArguments;
        int changed = 1; RuntimeException updateFailure;
        RecordingJdbc(RecordingTransactions tx) { this.tx = tx; }
        @Override public <T> T query(String sql, ResultSetExtractor<T> mapper, Object... args) {
            assertTrue(tx.active); events.add("lock"); lockSql = normalized(sql); lockArguments = args;
            try {
                when(rows.next()).thenReturn(present); when(rows.getString("nickname")).thenReturn("Alpha");
                when(rows.getString("role")).thenReturn(role.name()); when(rows.getString("care_reg_no")).thenReturn(care);
                return mapper.extractData(rows);
            } catch (SQLException e) { throw new AssertionError(e); }
        }
        @SuppressWarnings("unchecked")
        @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            assertTrue(tx.active); events.add("duplicate"); duplicateSql = normalized(sql); duplicateArguments = args;
            return (T) Boolean.valueOf(duplicate);
        }
        @Override public int update(PreparedStatementCreator creator) {
            assertTrue(tx.active); events.add("update"); var connection = mock(Connection.class);
            try {
                when(connection.prepareStatement(anyString())).thenAnswer(call -> { updateSql = normalized(call.getArgument(0)); return statement; });
                assertSame(statement, creator.createPreparedStatement(connection));
            } catch (SQLException e) { throw new UncategorizedSQLException("owned unit", updateSql, e); }
            if (updateFailure != null) throw updateFailure;
            return changed;
        }
        private static String normalized(String sql) { return sql.replaceAll("\\s+", " ").strip(); }
    }
    private static final class RecordingTransactions implements PlatformTransactionManager {
        boolean active; int starts, commits, rollbacks;
        RuntimeException beginFailure, commitFailure;
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            assertFalse(active); assertFalse(definition.isReadOnly()); assertEquals(15, definition.getTimeout());
            assertEquals(TransactionDefinition.ISOLATION_DEFAULT, definition.getIsolationLevel()); starts++;
            if (beginFailure != null) throw beginFailure;
            active = true; return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) { active = false; commits++; if (commitFailure != null) throw commitFailure; }
        @Override public void rollback(TransactionStatus status) { active = false; rollbacks++; }
    }
}
