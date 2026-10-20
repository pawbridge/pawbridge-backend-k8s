package com.pawbridge.backend.user.internal.animalcommand.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.animal.api.AnimalCommandActors.Actor;
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

class JdbcAnimalCommandActorsTest {
    private static final String MEMBER = "SELECT user_id,role,care_reg_no FROM pawbridge_user.users WHERE user_id=? FOR UPDATE";
    private static final String PENDING = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.contact_deletions WHERE user_id=?)";
    private final DataSource source = mock(DataSource.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ResultSet member = mock(ResultSet.class);
    private final ConnectionHolder resource = new ConnectionHolder(mock(Connection.class));
    private final JdbcAnimalCommandActors actors = new JdbcAnimalCommandActors(jdbc);

    @BeforeEach @SuppressWarnings({"unchecked", "rawtypes"})
    void givenSharedWriterTransactionAndAnExistingMember() throws Exception {
        TransactionSynchronizationManager.clear();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.bindResource(source, resource);
        when(jdbc.getDataSource()).thenReturn(source);
        when(member.getLong("user_id")).thenReturn(7L);
        when(jdbc.query(eq(MEMBER), any(RowMapper.class), eq(7L))).thenAnswer(call ->
                List.of(((RowMapper) call.getArgument(1)).mapRow(member, 0)));
        when(jdbc.queryForObject(PENDING, Boolean.class, 7L)).thenReturn(false);
    }

    @AfterEach
    void releaseOnlyTheTestTransactionBinding() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source);
        TransactionSynchronizationManager.clear();
    }

    @ParameterizedTest
    @CsvSource(value = {"ROLE_ADMIN,<null>", "ROLE_SHELTER,care-7"}, nullValues = "<null>")
    void givenCurrentRoleAndNullableShelterIdentity__whenFindingTheVerifiedPrincipal__thenMapOnlyTheCurrentLockedUser(String role, String care) throws Exception {
        when(member.getString("role")).thenReturn(role);
        when(member.getString("care_reg_no")).thenReturn(care);
        assertEquals(new Actor(7, role, care, true, false), actors.find(7));
        var order = inOrder(jdbc);
        order.verify(jdbc).query(eq(MEMBER), any(RowMapper.class), eq(7L));
        order.verify(jdbc).queryForObject(PENDING, Boolean.class, 7L);
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        assertSame(resource, TransactionSynchronizationManager.getResource(source));
        verifyNoInteractions(source);
    }

    @Test
    void givenPendingContactDeletion__whenFindingTheLockedUser__thenKeepRoleAndCareButReturnInactiveDeleted() throws Exception {
        when(member.getString("role")).thenReturn("ROLE_SHELTER");
        when(member.getString("care_reg_no")).thenReturn("care-7");
        when(jdbc.queryForObject(PENDING, Boolean.class, 7L)).thenReturn(true);
        assertEquals(new Actor(7, "ROLE_SHELTER", "care-7", false, true), actors.find(7));
        var order = inOrder(jdbc);
        order.verify(jdbc).query(eq(MEMBER), any(RowMapper.class), eq(7L));
        order.verify(jdbc).queryForObject(PENDING, Boolean.class, 7L);
    }

    @Test @SuppressWarnings("unchecked")
    void givenNoCurrentUser__whenFindingTheVerifiedPrincipal__thenReturnInactiveDeletedWithoutInventingARoleOrReadingPending() {
        when(jdbc.query(eq(MEMBER), any(RowMapper.class), eq(7L))).thenReturn(List.of());
        assertEquals(new Actor(7, null, null, false, true), actors.find(7));
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class), anyLong());
    }

    @Test
    void givenNoWritableTransactionOrOnlyAnotherDataSourceBinding__whenFinding__thenRejectBeforeAnySql() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(IllegalStateException.class, () -> actors.find(7));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThrows(IllegalStateException.class, () -> actors.find(7));
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.unbindResource(source);
        DataSource other = mock(DataSource.class);
        TransactionSynchronizationManager.bindResource(other, resource);
        try { assertThrows(IllegalStateException.class, () -> actors.find(7)); }
        finally { TransactionSynchronizationManager.unbindResource(other); }
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), anyLong());
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class), anyLong());
    }

    @Test
    void givenNonpositiveVerifiedIdentity__whenFinding__thenRejectBeforeAnySql() {
        assertAll(() -> assertThrows(IllegalArgumentException.class, () -> actors.find(0)),
                () -> assertThrows(IllegalArgumentException.class, () -> actors.find(-1)));
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), anyLong());
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class), anyLong());
    }

    @ParameterizedTest @ValueSource(strings = {"member", "pending"})
    @SuppressWarnings("unchecked")
    void givenSqlFailure__whenLockingOrObservingPendingDeletion__thenPropagateTheSameFailureAndDoNotContinue(String phase) {
        var failure = new DataAccessResourceFailureException("Synthetic lookup unavailable");
        if (phase.equals("member")) when(jdbc.query(eq(MEMBER), any(RowMapper.class), eq(7L))).thenThrow(failure);
        else when(jdbc.queryForObject(PENDING, Boolean.class, 7L)).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> actors.find(7)));
        if (phase.equals("member")) verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class), anyLong());
        else {
            var order = inOrder(jdbc);
            order.verify(jdbc).query(eq(MEMBER), any(RowMapper.class), eq(7L));
            order.verify(jdbc).queryForObject(PENDING, Boolean.class, 7L);
        }
        assertSame(resource, TransactionSynchronizationManager.getResource(source));
    }
}
