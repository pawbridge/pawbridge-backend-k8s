package com.pawbridge.backend.animal.internal.shelter.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.animal.api.AnimalShelterReadUnavailableException;
import com.pawbridge.backend.animal.api.AnimalShelterReads;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class MembershipLockedAnimalShelterReadsTest {
    private static final String SQL="SELECT id,care_reg_no,name FROM pawbridge_animal.shelters WHERE care_reg_no=? FOR KEY SHARE";
    private final DataSource source=mock(DataSource.class),other=mock(DataSource.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final AnimalShelterReads delegate=mock(AnimalShelterReads.class);
    private final ResultSet row=mock(ResultSet.class);
    private final ConnectionHolder binding=new ConnectionHolder(mock(Connection.class));
    private final AnimalShelterReads.Shelter snapshot=new AnimalShelterReads.Shelter(7L,"care","full detail name");
    private final MembershipLockedAnimalShelterReads reads=new MembershipLockedAnimalShelterReads(delegate,jdbc);

    @BeforeEach @SuppressWarnings({"unchecked","rawtypes"}) void bindCallerAndMatchingRow() throws Exception {
        TransactionSynchronizationManager.clear();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.bindResource(source,binding);
        when(jdbc.getDataSource()).thenReturn(source);
        when(delegate.findByCareRegNo("care")).thenReturn(snapshot);
        when(row.getObject("id",Long.class)).thenReturn(7L);when(row.getString("care_reg_no")).thenReturn("care");when(row.getString("name")).thenReturn("current name");
        when(jdbc.query(eq(SQL),any(RowMapper.class),eq("care"))).thenAnswer(call->List.of(((RowMapper)call.getArgument(1)).mapRow(row,0)));
    }
    @AfterEach void releaseOnlyTestBindings() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source);
        TransactionSynchronizationManager.unbindResourceIfPossible(other);
        TransactionSynchronizationManager.clear();
    }

    @Test void given__full_detail__when__confirming_membership__then__lock_after_delegate_and_return_its_unchanged_snapshot() {
        assertSame(snapshot,reads.findByCareRegNo("care"));
        var order=inOrder(delegate,jdbc);order.verify(delegate).findByCareRegNo("care");order.verify(jdbc).query(eq(SQL),any(RowMapper.class),eq("care"));
        assertSame(binding,TransactionSynchronizationManager.getResource(source));
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        verifyNoInteractions(source,other);
    }
    @Test void given__empty_delegate__when__confirming__then__keep_null_without_writer_query() {
        when(delegate.findByCareRegNo("care")).thenReturn(null);assertNull(reads.findByCareRegNo("care"));noSql();
    }
    @Test void given__full_detail_failure__when__confirming__then__preserve_failure_without_locking() {
        var failure=new AnimalShelterReadUnavailableException(new IllegalStateException("Synthetic public info failure"));
        when(delegate.findByCareRegNo("care")).thenThrow(failure);
        assertSame(failure,assertThrows(AnimalShelterReadUnavailableException.class,()->reads.findByCareRegNo("care")));noSql();
    }
    @ParameterizedTest @ValueSource(strings={"missing","id","registration","delegateRegistration","nullId"})
    @SuppressWarnings("unchecked")
    void given__changed_or_missing_row__when__locking__then__return_only_fixed_unavailable(String mode) throws Exception {
        if(mode.equals("missing"))when(jdbc.query(eq(SQL),any(RowMapper.class),eq("care"))).thenReturn(List.of());
        if(mode.equals("id"))when(row.getObject("id",Long.class)).thenReturn(8L);
        if(mode.equals("registration"))when(row.getString("care_reg_no")).thenReturn("other");
        if(mode.equals("delegateRegistration"))when(delegate.findByCareRegNo("care")).thenReturn(new AnimalShelterReads.Shelter(7L,"other","x"));
        if(mode.equals("nullId"))when(delegate.findByCareRegNo("care")).thenReturn(new AnimalShelterReads.Shelter(null,"care","x"));
        assertEquals("Animal shelter information could not be read",assertThrows(AnimalShelterReadUnavailableException.class,()->reads.findByCareRegNo("care")).getMessage());
    }
    @ParameterizedTest @ValueSource(strings={"inactive","readOnly","other","missingSource"})
    void given__no_writer_scope__when__confirming__then__reject_before_delegate_or_sql(String mode) {
        invalidate(mode);assertThrows(AnimalShelterReadUnavailableException.class,()->reads.findByCareRegNo("care"));verifyNoInteractions(delegate);noSql();
    }
    @ParameterizedTest @ValueSource(strings={"inactive","readOnly","other","replacement"})
    void given__delegate_changes_writer_scope__when__returning__then__reject_before_locking(String mode) {
        when(delegate.findByCareRegNo("care")).thenAnswer(call->{invalidate(mode);return snapshot;});
        assertThrows(AnimalShelterReadUnavailableException.class,()->reads.findByCareRegNo("care"));noSql();
    }
    @Test @SuppressWarnings("unchecked") void given__lock_query_failure__when__confirming__then__keep_unavailable_classification() {
        var failure=new DataAccessResourceFailureException("Synthetic shelter lock failure");when(jdbc.query(eq(SQL),any(RowMapper.class),eq("care"))).thenThrow(failure);
        assertSame(failure,assertThrows(AnimalShelterReadUnavailableException.class,()->reads.findByCareRegNo("care")).getCause());
        assertSame(binding,TransactionSynchronizationManager.getResource(source));
    }
    private void invalidate(String mode) {
        switch(mode) {
            case "inactive" -> TransactionSynchronizationManager.setActualTransactionActive(false);
            case "readOnly" -> TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            case "other" -> {TransactionSynchronizationManager.unbindResource(source);TransactionSynchronizationManager.bindResource(other,binding);}
            case "replacement" -> {TransactionSynchronizationManager.unbindResource(source);TransactionSynchronizationManager.bindResource(source,new ConnectionHolder(mock(Connection.class)));}
            default -> when(jdbc.getDataSource()).thenReturn(null);
        }
    }
    private void noSql(){verify(jdbc,never()).query(anyString(),any(RowMapper.class),any());}
}
