package com.pawbridge.backend.user.internal.storeorder.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.store.api.StoreOrderAdministrators.Actor;
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

class JdbcStoreOrderAdministratorsTest {
    private static final String LOCK = "SELECT user_id,role FROM pawbridge_user.users WHERE user_id=? FOR UPDATE";
    private static final String READ = """
            SELECT u.user_id, u.role, d.user_id IS NOT NULL AS pending
            FROM pawbridge_user.users u
            LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id
            WHERE u.user_id = ?
            """;
    private static final String PENDING="SELECT EXISTS(SELECT 1 FROM pawbridge_user.contact_deletions WHERE user_id=?)";
    private final DataSource source=mock(DataSource.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ResultSet member=mock(ResultSet.class);
    private final ConnectionHolder resource=new ConnectionHolder(mock(Connection.class));
    private final JdbcStoreOrderAdministrators administrators=new JdbcStoreOrderAdministrators(jdbc);

    @BeforeEach @SuppressWarnings({"unchecked","rawtypes"})
    void given__a_shared_writer_binding__when__preparing__then__observe_current_user_rows() throws Exception {
        TransactionSynchronizationManager.clear();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.bindResource(source,resource);
        when(jdbc.getDataSource()).thenReturn(source);
        when(member.getLong("user_id")).thenReturn(7L);
        when(member.getString("role")).thenReturn("ROLE_ADMIN");
        when(jdbc.query(anyString(),any(RowMapper.class),eq(7L))).thenAnswer(call->
                List.of(((RowMapper)call.getArgument(1)).mapRow(member,0)));
        when(jdbc.queryForObject(PENDING,Boolean.class,7L)).thenReturn(false);
    }
    @AfterEach void release__the_test_binding() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source);
        TransactionSynchronizationManager.clear();
    }
    @ParameterizedTest
    @CsvSource({"true,ROLE_ADMIN","false,ROLE_ADMIN","true,ROLE_USER","false,admin","false,ROLE_SHELTER"})
    void given__current_db_roles__when__observing_inside_the_matching_transaction__then__preserve_roles_and_lock_only_for_write(boolean write,String role)throws Exception {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(!write);
        when(member.getString("role")).thenReturn(role);
        assertEquals(new Actor(7,true,false,role),current(write));
        var order=inOrder(jdbc);order.verify(jdbc).query(eq(write?LOCK:READ),any(RowMapper.class),eq(7L));
        if(write)order.verify(jdbc).queryForObject(PENDING,Boolean.class,7L);
        else verify(jdbc,never()).queryForObject(anyString(),eq(Boolean.class),anyLong());
        verify(jdbc,never()).query(eq(write?READ:LOCK),any(RowMapper.class),anyLong());
        assertSame(resource,TransactionSynchronizationManager.getResource(source));
        assertEquals(!write,TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        verifyNoInteractions(source);
    }
    @ParameterizedTest @ValueSource(booleans={true,false}) @SuppressWarnings("unchecked")
    void given__a_missing_user__when__reading_current_authority__then__return_unavailable_without_pending_query(boolean write) {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(!write);
        when(jdbc.query(anyString(),any(RowMapper.class),eq(7L))).thenReturn(List.of());
        assertEquals(new Actor(7,false,true,null),current(write));
        verify(jdbc,never()).queryForObject(anyString(),eq(Boolean.class),anyLong());
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void given__pending_contact_deletion__when__reading_authority__then__keep_db_role_but_disable_the_actor(boolean write)throws Exception {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(!write);
        when(member.getBoolean("pending")).thenReturn(true);
        when(jdbc.queryForObject(PENDING,Boolean.class,7L)).thenReturn(true);
        assertEquals(new Actor(7,false,true,"ROLE_ADMIN"),current(write));
        var order=inOrder(jdbc);order.verify(jdbc).query(eq(write?LOCK:READ),any(RowMapper.class),eq(7L));
        if(write)order.verify(jdbc).queryForObject(PENDING,Boolean.class,7L);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void given__missing_or_wrong_transaction_state__when__reading_authority__then__reject_before_any_user_sql(boolean write) {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(!write);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(IllegalStateException.class,()->current(write));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.unbindResource(source);
        assertThrows(IllegalStateException.class,()->current(write));
        TransactionSynchronizationManager.bindResource(source,resource);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(write);
        assertThrows(IllegalStateException.class,()->current(write));
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(!write);
        when(jdbc.getDataSource()).thenReturn(null);
        assertThrows(IllegalStateException.class,()->current(write));
        noSql();
    }
    @Test
    void given__nonpositive_verified_ids__when__requesting_current_authority__then__reject_before_sql() {
        for(long id:List.of(0L,-1L)) {
            assertThrows(IllegalArgumentException.class,()->administrators.currentForRead(id));
            assertThrows(IllegalArgumentException.class,()->administrators.currentForWrite(id));
        }
        noSql();
    }
    @ParameterizedTest @CsvSource({"true,member","false,member","true,pending"}) @SuppressWarnings("unchecked")
    void given__sql_failure__when__observing_authority__then__propagate_the_same_failure_and_preserve_transaction_binding(boolean write,String phase) {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(!write);
        var failure=new DataAccessResourceFailureException("Synthetic authority unavailable");
        if(phase.equals("member"))when(jdbc.query(anyString(),any(RowMapper.class),eq(7L))).thenThrow(failure);
        else when(jdbc.queryForObject(PENDING,Boolean.class,7L)).thenThrow(failure);
        assertSame(failure,assertThrows(DataAccessResourceFailureException.class,()->current(write)));
        if(phase.equals("member"))verify(jdbc,never()).queryForObject(anyString(),eq(Boolean.class),anyLong());
        assertSame(resource,TransactionSynchronizationManager.getResource(source));
    }
    @Test
    void given__an_unavailable_pending_observation__when__reading_write_authority__then__never_invent_an_active_administrator() {
        when(jdbc.queryForObject(PENDING,Boolean.class,7L)).thenReturn(null);
        assertThrows(NullPointerException.class,()->administrators.currentForWrite(7));
    }
    private Actor current(boolean write){return write?administrators.currentForWrite(7):administrators.currentForRead(7);}
    private void noSql(){verify(jdbc,never()).query(anyString(),any(RowMapper.class),anyLong());verify(jdbc,never()).queryForObject(anyString(),eq(Boolean.class),anyLong());}
}
