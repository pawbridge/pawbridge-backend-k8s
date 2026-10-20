package com.pawbridge.backend.user.internal.shelter.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class JdbcAnimalShelterMembersTest {
    private static final String SQL = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.users WHERE care_reg_no=?)";
    private final DataSource source = mock(DataSource.class), other = mock(DataSource.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ConnectionHolder binding = new ConnectionHolder(mock(Connection.class));
    private final JdbcAnimalShelterMembers members = new JdbcAnimalShelterMembers(jdbc);

    @BeforeEach void bindCaller() {
        TransactionSynchronizationManager.clear();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.bindResource(source,binding);
        when(jdbc.getDataSource()).thenReturn(source);
    }
    @AfterEach void releaseOnlyTestBindings() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source);
        TransactionSynchronizationManager.unbindResourceIfPossible(other);
        TransactionSynchronizationManager.clear();
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void given__any_still_present_association__when__observing__then__bind_raw_registration_without_role_or_pending_filter(boolean exists) {
        when(jdbc.queryForObject(SQL,Boolean.class," care ")).thenReturn(exists);
        assertEquals(exists,members.exists(" care "));
        verify(jdbc).queryForObject(SQL,Boolean.class," care ");
        assertSame(binding,TransactionSynchronizationManager.getResource(source));
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        verifyNoInteractions(source,other);
    }

    @ParameterizedTest @ValueSource(strings={"inactive","readOnly","other","missingSource"})
    void given__no_matching_writer_scope__when__observing__then__reject_before_user_sql(String mode) {
        switch(mode) {
            case "inactive" -> TransactionSynchronizationManager.setActualTransactionActive(false);
            case "readOnly" -> TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            case "other" -> { TransactionSynchronizationManager.unbindResource(source);TransactionSynchronizationManager.bindResource(other,binding); }
            default -> when(jdbc.getDataSource()).thenReturn(null);
        }
        assertThrows(IllegalStateException.class,()->members.exists("care"));
        verify(jdbc,never()).queryForObject(anyString(),eq(Boolean.class),any());
    }

    @ParameterizedTest @ValueSource(strings={"null","sql"})
    void given__unavailable_observation__when__reading__then__fail_instead_of_allowing_deletion(String mode) {
        var failure=new DataAccessResourceFailureException("Synthetic member observation failure");
        if(mode.equals("sql"))when(jdbc.queryForObject(SQL,Boolean.class,"care")).thenThrow(failure);
        else when(jdbc.queryForObject(SQL,Boolean.class,"care")).thenReturn(null);
        if(mode.equals("sql"))assertSame(failure,assertThrows(DataAccessResourceFailureException.class,()->members.exists("care")));
        else assertThrows(NullPointerException.class,()->members.exists("care"));
        assertSame(binding,TransactionSynchronizationManager.getResource(source));
    }
}
