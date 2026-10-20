package com.pawbridge.backend.user.internal.session.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

class JdbcAuthSessionsTest {
    static final LocalDateTime NOW=LocalDateTime.of(2030,2,3,4,5,6,123456000);
    @Test void given__bad_signed_token__when__refreshing__then__invalid_before_queries_and_rollback() {
        var f=new Fixture();when(f.tokens.valid("supplied")).thenReturn(false);
        var failure=assertThrows(AuthSessionRejectedException.class,()->f.sessions.refresh("supplied"));
        assertEquals(AuthSessionRejectedException.Reason.INVALID,failure.reason());assertEquals(List.of("begin","rollback"),f.tx.events);
        assertEquals(0,f.samples);verify(f.tokens).valid("supplied");verifyNoMoreInteractions(f.tokens);
    }
    @ParameterizedTest @ValueSource(strings={"missing-token","expired","missing-user"})
    void given__missing_expired_or_orphaned_stored_token__when__refreshing__then__original_failure_order_without_update_or_expired_delete(String kind) {
        var f=new Fixture();f.jdbc.refreshPresent=!kind.equals("missing-token");f.jdbc.userPresent=!kind.equals("missing-user");
        if(kind.equals("expired"))f.jdbc.expiry=NOW.minusNanos(1000);
        if(kind.equals("missing-user"))assertThrows(UserNotFoundException.class,()->f.sessions.refresh("supplied"));
        else {
            var failure=assertThrows(AuthSessionRejectedException.class,()->f.sessions.refresh("supplied"));
            assertEquals(kind.equals("expired")?AuthSessionRejectedException.Reason.EXPIRED:AuthSessionRejectedException.Reason.MISSING,failure.reason());
        }
        assertEquals(1,f.tx.rollbacks);assertEquals(0,f.tx.commits);assertEquals(0,f.jdbc.writes);
        assertEquals(kind.equals("missing-user"),f.tx.events.contains("user"));verify(f.tokens).valid("supplied");verifyNoMoreInteractions(f.tokens);
    }
    @Test void given__expiry_exactly_now_and_current_user__when__refreshing__then__same_writer_transaction_claims_and_unconditional_id_update() throws Exception {
        var f=new Fixture();f.jdbc.expiry=NOW;
        var result=f.sessions.refresh("supplied");
        assertTrue(result.accessToken().equals("synthetic-access"));assertTrue(result.refreshToken().equals("synthetic-refresh"));
        assertEquals(List.of("begin","refresh","now","user","now","update","commit"),f.tx.events);
        verify(f.tokens).access(new JwtSessionTokens.UserClaims(7,"synthetic@fixture.invalid","Synthetic","ROLE_ADMIN","CARE"));
        assertEquals("UPDATE pawbridge_user.refresh_tokens SET token = ?, expires_at = ? WHERE refresh_token_id = ?",f.jdbc.sql);
        verify(f.jdbc.statement).setString(1,"synthetic-refresh");verify(f.jdbc.statement).setLong(3,11);
        var calendar=org.mockito.ArgumentCaptor.forClass(Calendar.class);
        verify(f.jdbc.statement).setTimestamp(eq(2),eq(Timestamp.valueOf(NOW.plusSeconds(1))),calendar.capture());
        assertEquals("UTC",calendar.getValue().getTimeZone().getID());assertEquals(2,f.samples);assertEquals(1,f.tx.commits);assertFalse(f.tx.active);
    }
    @ParameterizedTest @ValueSource(strings={"begin","update","commit"})
    void given__transaction_or_write_failure__when__refreshing__then__propagate_without_token_response_retry_or_rollback_reclassification(String phase) {
        var f=new Fixture();var failure=new TransactionSystemException("synthetic boundary");
        if(phase.equals("begin"))f.tx.beginFailure=failure;else if(phase.equals("commit"))f.tx.commitFailure=failure;else f.jdbc.failure=failure;
        assertSame(failure,assertThrows(RuntimeException.class,()->f.sessions.refresh("supplied")));
        assertEquals(1,f.tx.starts);assertEquals(phase.equals("update")?1:0,f.tx.rollbacks);assertFalse(f.tx.active);
    }
    @ParameterizedTest @ValueSource(strings={"refresh","logout"})
    void given__a_selected_row_disappears_before_dml__when__writing__then__stale_failure_and_transaction_rollback(String operation) {
        var f=new Fixture();f.jdbc.changed=0;
        assertThrows(OptimisticLockingFailureException.class,()->{if(operation.equals("refresh"))f.sessions.refresh("supplied");else f.sessions.logout(7);});
        assertEquals(1,f.tx.rollbacks);assertEquals(0,f.tx.commits);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__zero_or_multiple_selected_sessions__when__logout__then__delete_only_the_selected_ids_and_commit_without_account_or_token_checks(boolean present) {
        var f=new Fixture();f.jdbc.ids=present?List.of(11L,12L):List.of();
        f.sessions.logout(7);
        assertEquals(present?List.of("begin","logout-select","delete-11","delete-12","commit"):List.of("begin","logout-select","commit"),f.tx.events);
        assertEquals(1,f.tx.commits);assertEquals(0,f.samples);verifyNoInteractions(f.tokens);
    }
    @Test void given__prepared_binding_failure__when__rotating__then__close_statement_and_rollback() throws Exception {
        var f=new Fixture();var failure=new SQLException("synthetic bind");doThrow(failure).when(f.jdbc.statement).setString(1,"synthetic-refresh");
        assertSame(failure,assertThrows(UncategorizedSQLException.class,()->f.sessions.refresh("supplied")).getSQLException());
        verify(f.jdbc.statement).close();assertEquals(1,f.tx.rollbacks);
    }
    static final class Fixture {
        final Tx tx=new Tx();final RecordingJdbc jdbc=new RecordingJdbc(tx);final JwtSessionTokens tokens=mock(JwtSessionTokens.class);
        final JdbcAuthSessions sessions;int samples;
        Fixture(){when(tokens.valid("supplied")).thenReturn(true);when(tokens.access(any())).thenReturn("synthetic-access");when(tokens.refresh()).thenReturn("synthetic-refresh");when(tokens.refreshExpiration()).thenReturn(1234L);
            var transaction=new TransactionTemplate(tx);transaction.setTimeout(15);
            sessions=new JdbcAuthSessions(jdbc,transaction,tokens,()->{assertTrue(tx.active);samples++;tx.events.add("now");return NOW;});}
    }
    static final class RecordingJdbc extends JdbcTemplate {
        final Tx tx;final PreparedStatement statement=mock(PreparedStatement.class);boolean refreshPresent=true,userPresent=true;LocalDateTime expiry=NOW.plusDays(1);
        List<Long> ids=List.of(11L);int changed=1,writes;String sql;RuntimeException failure;
        RecordingJdbc(Tx tx){this.tx=tx;}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object...args){
            assertTrue(tx.active);var row=mock(ResultSet.class);
            try {
                if(sql.startsWith("SELECT refresh_token_id, token")){
                    assertArrayEquals(new Object[]{"supplied"},args);tx.events.add("refresh");if(!refreshPresent)return List.of();
                    when(row.getLong("refresh_token_id")).thenReturn(11L);when(row.getLong("user_id")).thenReturn(7L);when(row.getTimestamp(eq("expires_at"),any(Calendar.class))).thenReturn(Timestamp.valueOf(expiry));
                }else if(sql.contains("FROM pawbridge_user.users")){
                    assertArrayEquals(new Object[]{7L},args);tx.events.add("user");if(!userPresent)return List.of();
                    when(row.getLong("user_id")).thenReturn(7L);when(row.getString("email")).thenReturn("synthetic@fixture.invalid");when(row.getString("name")).thenReturn("Synthetic");when(row.getString("role")).thenReturn("ROLE_ADMIN");when(row.getString("care_reg_no")).thenReturn("CARE");
                }else{
                    assertEquals("SELECT refresh_token_id FROM pawbridge_user.refresh_tokens WHERE user_id = ?",sql);assertArrayEquals(new Object[]{7L},args);tx.events.add("logout-select");
                    var result=new ArrayList<T>();for(long id:ids){when(row.getLong(1)).thenReturn(id);result.add(mapper.mapRow(row,result.size()));}return result;
                }
                return List.of(mapper.mapRow(row,0));
            }catch(SQLException e){throw new AssertionError(e);}
        }
        @Override public int update(PreparedStatementCreator creator){assertTrue(tx.active);tx.events.add("update");writes++;
            try{var connection=mock(Connection.class);when(connection.prepareStatement(anyString())).thenAnswer(call->{sql=call.getArgument(0);return statement;});creator.createPreparedStatement(connection);}
            catch(SQLException e){throw new UncategorizedSQLException("owned unit",sql,e);}if(failure!=null)throw failure;return changed;}
        @Override public int update(String sql,Object...args){assertTrue(tx.active);assertEquals("DELETE FROM pawbridge_user.refresh_tokens WHERE refresh_token_id = ?",sql);tx.events.add("delete-"+args[0]);writes++;return changed;}
    }
    static final class Tx implements PlatformTransactionManager {
        final List<String> events=new ArrayList<>();boolean active;int starts,commits,rollbacks;RuntimeException beginFailure,commitFailure;
        public TransactionStatus getTransaction(TransactionDefinition d){assertFalse(active);assertFalse(d.isReadOnly());assertEquals(15,d.getTimeout());starts++;events.add("begin");if(beginFailure!=null)throw beginFailure;active=true;return new SimpleTransactionStatus();}
        public void commit(TransactionStatus s){active=false;commits++;events.add("commit");if(commitFailure!=null)throw commitFailure;}
        public void rollback(TransactionStatus s){active=false;rollbacks++;events.add("rollback");}
    }
}
