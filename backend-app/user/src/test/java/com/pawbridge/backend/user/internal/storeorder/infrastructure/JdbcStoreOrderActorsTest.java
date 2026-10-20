package com.pawbridge.backend.user.internal.storeorder.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.store.api.StoreOrderActors.Actor;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class JdbcStoreOrderActorsTest {
    private static final String MEMBER = "SELECT user_id FROM pawbridge_user.users WHERE user_id=?";
    private static final String LOCK_MEMBER = MEMBER + " FOR UPDATE";
    private static final String READ_MEMBER = """
            SELECT u.user_id, d.user_id IS NOT NULL AS pending
            FROM pawbridge_user.users u
            LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id
            WHERE u.user_id = ?
            """;
    private static final String PENDING = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.contact_deletions WHERE user_id=?)";
    private final DataSource source = mock(DataSource.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ResultSet member = mock(ResultSet.class);
    private final ConnectionHolder resource = new ConnectionHolder(mock(Connection.class));
    private final JdbcStoreOrderActors actors = new JdbcStoreOrderActors(jdbc);

    @BeforeEach @SuppressWarnings({"unchecked", "rawtypes"})
    void givenTheSharedWriterBindingAndAnExistingMember() throws Exception {
        TransactionSynchronizationManager.clear();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.bindResource(source, resource);
        when(jdbc.getDataSource()).thenReturn(source);
        when(member.getLong("user_id")).thenReturn(7L);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(7L))).thenAnswer(call ->
                List.of(((RowMapper) call.getArgument(1)).mapRow(member, 0)));
        when(jdbc.queryForObject(PENDING, Boolean.class, 7L)).thenReturn(false);
    }

    @AfterEach
    void releaseOnlyTheTestBinding() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source);
        TransactionSynchronizationManager.clear();
    }

    @ParameterizedTest @ValueSource(strings = {"write", "readOnly", "readWrite"})
    void givenTheCurrentMemberAndCallerTransaction__whenReadingState__thenLockOnlyForWriteAndObserveReadStateInOneStatement(String mode) {
        boolean write = mode.equals("write"), readOnly = mode.equals("readOnly");
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
        assertEquals(new Actor(7, true, false), current(write));
        var order = inOrder(jdbc);
        order.verify(jdbc).query(eq(write ? LOCK_MEMBER : READ_MEMBER), any(RowMapper.class), eq(7L));
        if (write) order.verify(jdbc).queryForObject(PENDING, Boolean.class, 7L);
        else verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class), anyLong());
        verify(jdbc, never()).query(eq(write ? READ_MEMBER : LOCK_MEMBER), any(RowMapper.class), anyLong());
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(readOnly, TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        assertSame(resource, TransactionSynchronizationManager.getResource(source));
        verifyNoInteractions(source);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    @SuppressWarnings("unchecked")
    void givenNoCurrentUser__whenReadingForWriteOrRead__thenReturnInactiveDeletedWithoutReadingPending(boolean write) {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(7L))).thenReturn(List.of());
        assertEquals(new Actor(7, false, true), current(write));
        verify(jdbc).query(eq(write ? LOCK_MEMBER : READ_MEMBER), any(RowMapper.class), eq(7L));
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class), anyLong());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void givenPendingContactDeletion__whenReadingForWriteOrRead__thenReturnInactiveDeletedAfterTheMemberQuery(boolean write) throws Exception {
        when(member.getBoolean("pending")).thenReturn(true);
        when(jdbc.queryForObject(PENDING, Boolean.class, 7L)).thenReturn(true);
        assertEquals(new Actor(7, false, true), current(write));
        var order = inOrder(jdbc);
        order.verify(jdbc).query(eq(write ? LOCK_MEMBER : READ_MEMBER), any(RowMapper.class), eq(7L));
        if (write) order.verify(jdbc).queryForObject(PENDING, Boolean.class, 7L);
        else verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class), anyLong());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void givenNoActualTransactionOrNoMatchingDataSourceBinding__whenReadingEitherMode__thenRejectBeforeSql(boolean write) {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(IllegalStateException.class, () -> current(write));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.unbindResource(source);
        DataSource other = mock(DataSource.class);
        TransactionSynchronizationManager.bindResource(other, resource);
        try { assertThrows(IllegalStateException.class, () -> current(write)); }
        finally { TransactionSynchronizationManager.unbindResource(other); }
        TransactionSynchronizationManager.bindResource(source, resource);
        when(jdbc.getDataSource()).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> current(write));
        verifyNoMemberSql();
    }

    @Test
    void givenTheBoundReadOnlyTransaction__whenRequestingWriteState__thenRejectBeforeTakingAUserRowLock() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThrows(IllegalStateException.class, () -> actors.currentForWrite(7));
        verifyNoMemberSql();
    }

    @Test
    void givenNonpositiveVerifiedIdentity__whenReadingEitherMode__thenRejectBeforeSql() {
        assertAll(() -> assertThrows(IllegalArgumentException.class, () -> actors.currentForWrite(0)),
                () -> assertThrows(IllegalArgumentException.class, () -> actors.currentForWrite(-1)),
                () -> assertThrows(IllegalArgumentException.class, () -> actors.currentForRead(0)),
                () -> assertThrows(IllegalArgumentException.class, () -> actors.currentForRead(-1)));
        verifyNoMemberSql();
    }

    @ParameterizedTest @CsvSource({"true,member", "false,member", "true,pending"})
    @SuppressWarnings("unchecked")
    void givenSqlFailure__whenReadingTheMemberOrPendingState__thenPropagateTheSameFailureWithoutFurtherWork(boolean write, String phase) {
        var failure = new DataAccessResourceFailureException("Synthetic current member unavailable");
        if (phase.equals("member")) when(jdbc.query(anyString(), any(RowMapper.class), eq(7L))).thenThrow(failure);
        else when(jdbc.queryForObject(PENDING, Boolean.class, 7L)).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> current(write)));
        if (phase.equals("member")) verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class), anyLong());
        else {
            var order = inOrder(jdbc);
            order.verify(jdbc).query(eq(LOCK_MEMBER), any(RowMapper.class), eq(7L));
            order.verify(jdbc).queryForObject(PENDING, Boolean.class, 7L);
        }
        assertSame(resource, TransactionSynchronizationManager.getResource(source));
    }

    private Actor current(boolean write) { return write ? actors.currentForWrite(7) : actors.currentForRead(7); }
    private void verifyNoMemberSql() {
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), anyLong());
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class), anyLong());
    }
}
