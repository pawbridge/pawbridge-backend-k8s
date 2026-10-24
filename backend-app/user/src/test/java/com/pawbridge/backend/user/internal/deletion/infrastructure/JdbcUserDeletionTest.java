package com.pawbridge.backend.user.internal.deletion.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.user.internal.deletion.application.MemberCleanupUnavailableException;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcUserDeletionTest {
    @Test
    void givenUser__whenDeleting__thenCommitPendingBeforeCommunityAndEraseOnlyAfterCommunityOutsideEitherTransaction() {
        var f = new Fixture();

        f.deletion.delete(7);

        assertEquals(List.of("begin", "lock", "pending", "commit", "cleanup", "community",
                "begin", "favorites", "shelter_applications", "lookup", "users", "commit", "cleanup"), f.events);
        assertEquals(2, f.manager.commits);
        assertEquals(0, f.manager.rollbacks);
        assertFalse(f.state.user);
        assertFalse(f.state.pending);
        assertFalse(f.state.favorites || f.state.applications);
        f.returned();
    }

    @Test
    void givenAuthSessions__whenDeletionStarts__thenRevokeEverySessionInThePendingMarkerTransaction() {
        var f = new Fixture();
        var tx = new TransactionTemplate(f.manager);
        tx.setTimeout(15);
        var deletion = new JdbcUserDeletion(f.jdbc, tx, id -> f.events.add("community"), userId -> {
            assertTrue(f.manager.active);
            f.events.add("revoke-" + userId);
        });

        deletion.delete(7);

        assertEquals(List.of("begin", "lock", "pending", "revoke-7", "commit", "cleanup", "community",
                "begin", "favorites", "shelter_applications", "lookup", "users", "commit", "cleanup"), f.events);
        f.returned();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 7})
    void givenAbsentIdIncludingNonpositiveId__whenDeleting__thenQueryTheOriginalIdAndFailMissingWithoutCommunityOrPending(long id) {
        var f = new Fixture();
        f.state.user = false;

        assertThrows(UserNotFoundException.class, () -> f.deletion.delete(id));

        assertEquals(List.of("begin", "lock", "rollback", "cleanup"), f.events);
        assertEquals(List.of(id), f.jdbc.ids);
        assertFalse(f.state.pending);
        assertEquals(0, f.communityCalls);
        f.returned();
    }

    @Test
    void givenCommunityFailureAfterPendingCommit__whenAuthorizedRetrySucceeds__thenKeepPendingAndResumeWithoutReactivation() {
        var f = new Fixture();
        f.communityFails = true;

        var failure = assertThrows(MemberCleanupUnavailableException.class, () -> f.deletion.delete(7));

        assertEquals("쪽지 정리가 완료되지 않았습니다. 회원 삭제를 다시 시도해 주세요.", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(List.of("begin", "lock", "pending", "commit", "cleanup", "community"), f.events);
        assertTrue(f.state.user && f.state.pending && f.state.favorites && f.state.applications);
        assertEquals(1, f.manager.commits);
        f.returned();
        f.communityFails = false;
        f.events.clear();

        f.deletion.delete(7);

        assertEquals(2, f.communityCalls);
        assertEquals(3, f.manager.commits);
        assertFalse(f.state.user);
        assertFalse(f.state.pending);
        assertEquals(List.of("begin", "lock", "pending", "commit", "cleanup", "community",
                "begin", "favorites", "shelter_applications", "lookup", "users", "commit", "cleanup"), f.events);
        f.returned();
    }

    @Test
    void givenPendingCommitFailure__whenDeleting__thenPropagateItBeforeCommunityWithoutClaimingDatabaseRollback() {
        var f = new Fixture();
        var failure = new TransactionSystemException("private-pending-commit");
        f.manager.commitFailure = failure;

        assertSame(failure, assertThrows(TransactionSystemException.class, () -> f.deletion.delete(7)));

        assertEquals(0, f.communityCalls);
        assertEquals(List.of("begin", "lock", "pending", "commit", "cleanup"), f.events);
        // A commit exception alone cannot determine the database's committed state.
        f.returned();
    }

    @ParameterizedTest
    @EnumSource(FinalDelete.class)
    void givenFinalAccountSqlFailure__whenRetryingAfterTheSameFailurePropagates__thenPreserveCommittedPendingAndResumeBothSteps(FinalDelete phase) {
        var f = new Fixture();
        f.jdbc.failedDelete = phase;
        var failure = new DataAccessResourceFailureException("private-final-sql");
        f.jdbc.failure = failure;

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> f.deletion.delete(7)));

        assertEquals(1, f.manager.commits);
        assertEquals(1, f.manager.rollbacks);
        assertTrue(f.state.user && f.state.pending && f.state.favorites && f.state.applications);
        var deletes = Arrays.stream(FinalDelete.values()).limit(phase.ordinal() + 1).map(v -> v.table).toList();
        assertEquals(deletes, f.events.subList(7, f.events.size() - 2).stream().filter(v -> !v.equals("lookup")).toList());
        assertEquals(phase == FinalDelete.USER, f.events.contains("lookup"));
        f.returned();
        f.jdbc.failedDelete = null;

        f.deletion.delete(7);

        assertEquals(2, f.communityCalls);
        assertFalse(f.state.user);
        assertFalse(f.state.pending);
        f.returned();
    }

    @Test
    void givenMissingUserAtFinalLookup__whenFinishingAfterCommunity__thenKeepOriginalNoopSuccessWithoutFinalDelete() {
        var f = new Fixture();
        f.jdbc.finalLookupMissing = true;

        f.deletion.delete(7);

        assertEquals(List.of("begin", "lock", "pending", "commit", "cleanup", "community",
                "begin", "favorites", "shelter_applications", "lookup", "commit", "cleanup"), f.events);
        assertEquals(2, f.manager.commits);
        assertEquals(0, f.manager.rollbacks);
        f.returned();
    }

    @Test
    void givenUserFoundButFinalDeleteAffectsZeroRows__whenFinishingAfterCommunity__thenRollbackFinalTransactionAsStale() {
        var f = new Fixture();
        f.jdbc.finalDeleteCount = 0;

        assertThrows(OptimisticLockingFailureException.class, () -> f.deletion.delete(7));

        assertEquals(1, f.manager.commits);
        assertEquals(1, f.manager.rollbacks);
        assertTrue(f.state.user && f.state.pending && f.state.favorites && f.state.applications);
        assertEquals(List.of("begin", "lock", "pending", "commit", "cleanup", "community",
                "begin", "favorites", "shelter_applications", "lookup", "users", "rollback", "cleanup"), f.events);
        f.returned();
    }

    private enum FinalDelete {
        FAVORITES("favorites"), APPLICATIONS("shelter_applications"), USER("users");
        final String table;
        FinalDelete(String table) { this.table = table; }
    }

    private static final class State {
        boolean user = true, pending, favorites = true, applications = true;
        boolean[] values() { return new boolean[]{user, pending, favorites, applications}; }
        void restore(boolean[] v) { user = v[0]; pending = v[1]; favorites = v[2]; applications = v[3]; }
    }

    private static final class Fixture {
        final State state = new State();
        final List<String> events = new ArrayList<>();
        final Manager manager = new Manager(state, events);
        final RecordingJdbc jdbc = new RecordingJdbc(this);
        final JdbcUserDeletion deletion;
        int communityCalls;
        boolean communityFails;

        Fixture() {
            var tx = new TransactionTemplate(manager);
            tx.setTimeout(15);
            deletion = new JdbcUserDeletion(jdbc, tx, id -> {
                assertEquals(7L, id);
                assertFalse(manager.active);
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
                assertTrue(state.pending);
                assertTrue(manager.commits > 0);
                events.add("community"); communityCalls++;
                if (communityFails) throw new IllegalStateException("private-community-body-token");
            });
        }

        void returned() {
            assertFalse(manager.active);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        final Fixture f;
        final List<Long> ids = new ArrayList<>();
        FinalDelete failedDelete;
        RuntimeException failure;
        boolean finalLookupMissing;
        int finalDeleteCount = 1;
        RecordingJdbc(Fixture f) { this.f = f; }

        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            assertTrue(f.manager.active);
            boolean finalLookup = sql.equals("SELECT user_id FROM pawbridge_user.users WHERE user_id=?");
            if (!finalLookup) assertEquals("SELECT user_id FROM pawbridge_user.users WHERE user_id=? FOR UPDATE", sql);
            assertEquals(1, args.length);
            ids.add((Long) args[0]); f.events.add(finalLookup ? "lookup" : "lock");
            if (!f.state.user || finalLookup && finalLookupMissing) return List.of();
            try {
                var row = mock(ResultSet.class);
                when(row.getLong(1)).thenReturn((Long) args[0]);
                return List.of(mapper.mapRow(row, 0));
            } catch (SQLException failure) { throw new AssertionError(failure); }
        }

        @Override public int update(String sql, Object... args) {
            assertTrue(f.manager.active);
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertArrayEquals(new Object[]{7L}, args);
            if (sql.startsWith("INSERT")) {
                assertEquals("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES (?) ON CONFLICT DO NOTHING", sql);
                f.events.add("pending"); boolean existed = f.state.pending; f.state.pending = true; return existed ? 0 : 1;
            }
            var phase = Arrays.stream(FinalDelete.values())
                    .filter(v -> sql.equals("DELETE FROM pawbridge_user." + v.table + " WHERE user_id=?"))
                    .findFirst().orElseThrow(() -> new AssertionError("Unexpected deletion SQL"));
            f.events.add(phase.table);
            if (phase == failedDelete) throw failure;
            if (phase == FinalDelete.USER && finalDeleteCount != 1) return finalDeleteCount;
            switch (phase) {
                case FAVORITES -> f.state.favorites = false;
                case APPLICATIONS -> f.state.applications = false;
                case USER -> { f.state.user = false; f.state.pending = false; }
            }
            return 1;
        }
    }

    /** Recording state and real Spring synchronization only; no PostgreSQL atomicity or FK proof. */
    private static final class Manager extends AbstractPlatformTransactionManager {
        final State state;
        final List<String> events;
        boolean active;
        boolean[] before;
        int commits, rollbacks;
        RuntimeException commitFailure;
        Manager(State state, List<String> events) { this.state = state; this.events = events; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) {
            assertEquals(15, definition.getTimeout()); assertFalse(definition.isReadOnly());
            before = state.values(); active = true; events.add("begin");
        }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
            if (commitFailure != null) throw commitFailure;
            commits++;
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; state.restore(before); events.add("rollback"); }
        @Override protected void doCleanupAfterCompletion(Object tx) { active = false; events.add("cleanup"); }
    }
}
