package com.pawbridge.backend.user.internal.login.infrastructure;

import com.pawbridge.backend.user.internal.login.application.LocalLogin;
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.*;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcLocalLoginTest {
    static final LocalDateTime NOW=LocalDateTime.of(2030,2,3,4,5,6,123456000);
    static final LocalLogin.Account ACCOUNT=new LocalLogin.Account(7,"stored@fixture.invalid","Stored","synthetic-hash","ROLE_SHELTER","CARE");

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"OAuth2","synthetic-hash"})
    void given__stored_password_without_provider_filter__when__finding_casefold_email__then__return_original_account_without_transaction(String password) {
        var f=new Fixture();f.jdbc.password=password;
        var account=f.login.findByEmail("MIXED@fixture.invalid").orElseThrow();
        assertEquals(password,account.password());assertEquals(7,account.userId());assertEquals("ROLE_SHELTER",account.role());
        assertEquals("SELECT user_id, email, name, password, role, care_reg_no FROM pawbridge_user.users WHERE lower(email) = lower(?)",f.jdbc.lastSql);
        assertArrayEquals(new Object[]{"MIXED@fixture.invalid"},f.jdbc.lastArgs);assertEquals(List.of("user-query"),f.events);
        assertEquals("LocalLogin.Account",account.toString());verifyNoInteractions(f.tokens);
    }
    @ParameterizedTest @ValueSource(ints={0,2})
    void given__absent_or_nonunique_email__when__finding__then__preserve_optional_or_single_result_failure(int count) {
        var f=new Fixture();f.jdbc.userRows=count;
        if(count==0)assertTrue(f.login.findByEmail("synthetic@fixture.invalid").isEmpty());
        else assertThrows(IncorrectResultSizeDataAccessException.class,()->f.login.findByEmail("synthetic@fixture.invalid"));
        assertEquals(List.of("user-query"),f.events);verifyNoInteractions(f.tokens);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__new_or_existing_refresh__when__completing__then__lookup_outside_save_transaction_and_keep_original_bindings(boolean existing)throws Exception {
        var f=new Fixture();f.jdbc.ids=existing?List.of(31L):List.of();
        var result=f.login.complete(ACCOUNT);
        assertEquals("safe-access",result.accessToken());assertEquals("safe-refresh",result.refreshToken());
        assertEquals(List.of("access","refresh","refresh-query","begin",existing?"update":"insert","commit"),f.events);
        verify(f.tokens).access(new JwtSessionTokens.UserClaims(7,ACCOUNT.email(),ACCOUNT.name(),ACCOUNT.role(),ACCOUNT.careRegNo()));
        verify(f.jdbc.statement).setString(1,"safe-refresh");
        var calendar=org.mockito.ArgumentCaptor.forClass(Calendar.class);
        if(existing){
            assertEquals("UPDATE pawbridge_user.refresh_tokens SET token = ?, expires_at = ? WHERE refresh_token_id = ?",f.jdbc.lastSql);
            verify(f.jdbc.statement).setTimestamp(eq(2),eq(Timestamp.valueOf(NOW.plusSeconds(1))),calendar.capture());
            verify(f.jdbc.statement).setLong(3,31L);assertEquals(List.of(false),f.clockTransactions);
        }else{
            assertEquals("INSERT INTO pawbridge_user.refresh_tokens(token,user_id,expires_at,created_at) VALUES (?,?,?,?)",f.jdbc.lastSql);
            verify(f.jdbc.statement).setLong(2,7L);
            verify(f.jdbc.statement).setTimestamp(eq(3),eq(Timestamp.valueOf(NOW.plusSeconds(1))),calendar.capture());
            verify(f.jdbc.statement).setTimestamp(eq(4),eq(Timestamp.valueOf(NOW)),any(Calendar.class));assertEquals(List.of(false,true),f.clockTransactions);
        }
        assertEquals("UTC",calendar.getValue().getTimeZone().getID());assertFalse(f.tx.active);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__write_or_commit_failure__when__saving__then__propagate_without_retry_or_success_result(boolean commit) {
        var f=new Fixture();RuntimeException failure=commit?new TransactionSystemException("synthetic commit"):new DataAccessResourceFailureException("synthetic write");
        if(commit)f.tx.commitFailure=failure;else f.jdbc.writeFailure=failure;
        assertSame(failure,assertThrows(RuntimeException.class,()->f.login.complete(ACCOUNT)));
        assertEquals(List.of("access","refresh","refresh-query","begin","insert",commit?"commit":"rollback"),f.events);
        assertEquals(1,f.jdbc.writes);
    }
    @Test void given__multiple_refresh_rows__when__completing__then__fail_before_save_transaction_without_arbitrary_choice() {
        var f=new Fixture();f.jdbc.ids=List.of(1L,2L);
        assertThrows(IncorrectResultSizeDataAccessException.class,()->f.login.complete(ACCOUNT));
        assertEquals(List.of("access","refresh","refresh-query"),f.events);assertEquals(0,f.jdbc.writes);
    }
    @Test void given__selected_row_disappears__when__saving__then__rollback_original_stale_write_failure() {
        var f=new Fixture();f.jdbc.ids=List.of(31L);f.jdbc.changed=0;
        assertThrows(OptimisticLockingFailureException.class,()->f.login.complete(ACCOUNT));
        assertEquals(List.of("access","refresh","refresh-query","begin","update","rollback"),f.events);
    }
    @Test void given__prepared_binding_failure__when__saving__then__close_statement_and_rollback()throws Exception {
        var f=new Fixture();var failure=new SQLException("synthetic bind");doThrow(failure).when(f.jdbc.statement).setString(1,"safe-refresh");
        assertSame(failure,assertThrows(UncategorizedSQLException.class,()->f.login.complete(ACCOUNT)).getSQLException());
        verify(f.jdbc.statement).close();assertEquals("rollback",f.events.getLast());
    }
    static final class Fixture {
        final List<String> events=new ArrayList<>();final Tx tx=new Tx(events);final RecordingJdbc jdbc=new RecordingJdbc(tx,events);
        final List<Boolean> clockTransactions=new ArrayList<>();final JwtSessionTokens tokens=mock(JwtSessionTokens.class);final JdbcLocalLogin login;
        Fixture(){
            when(tokens.access(any())).thenAnswer(call->{events.add("access");return "safe-access";});
            when(tokens.refresh()).thenAnswer(call->{events.add("refresh");return "safe-refresh";});when(tokens.refreshExpiration()).thenReturn(1234L);
            login=new JdbcLocalLogin(jdbc,new TransactionTemplate(tx),tokens,()->{clockTransactions.add(tx.active);return NOW;});
        }
    }
    static final class RecordingJdbc extends JdbcTemplate {
        final Tx tx;final List<String> events;final PreparedStatement statement=mock(PreparedStatement.class);
        List<Long> ids=List.of();String password="synthetic-hash",lastSql;Object[] lastArgs;int userRows=1,changed=1,writes;RuntimeException writeFailure;
        RecordingJdbc(Tx tx,List<String> events){this.tx=tx;this.events=events;}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object...args){
            assertFalse(tx.active);lastSql=sql;lastArgs=args;var result=new ArrayList<T>();
            try{
                if(sql.contains("FROM pawbridge_user.users")){
                    events.add("user-query");
                    for(int i=0;i<userRows;i++){var row=mock(ResultSet.class);when(row.getLong("user_id")).thenReturn(7L);
                        when(row.getString("email")).thenReturn(ACCOUNT.email());when(row.getString("name")).thenReturn(ACCOUNT.name());
                        when(row.getString("password")).thenReturn(password);when(row.getString("role")).thenReturn(ACCOUNT.role());
                        when(row.getString("care_reg_no")).thenReturn(ACCOUNT.careRegNo());result.add(mapper.mapRow(row,i));}
                }else{
                    assertEquals("SELECT refresh_token_id FROM pawbridge_user.refresh_tokens WHERE user_id = ?",sql);assertArrayEquals(new Object[]{7L},args);events.add("refresh-query");
                    for(long id:ids){var row=mock(ResultSet.class);when(row.getLong("refresh_token_id")).thenReturn(id);result.add(mapper.mapRow(row,result.size()));}
                }
                return result;
            }catch(SQLException failure){throw new AssertionError(failure);}
        }
        @Override public int update(PreparedStatementCreator creator){
            assertTrue(tx.active);writes++;
            try{
                var connection=mock(Connection.class);when(connection.prepareStatement(anyString())).thenAnswer(call->{lastSql=call.getArgument(0);events.add(lastSql.startsWith("UPDATE")?"update":"insert");return statement;});
                creator.createPreparedStatement(connection);if(writeFailure!=null)throw writeFailure;return changed;
            }catch(SQLException failure){throw new UncategorizedSQLException("synthetic binding",lastSql,failure);}
        }
    }
    static final class Tx implements PlatformTransactionManager {
        final List<String> events;boolean active;RuntimeException commitFailure;Tx(List<String> events){this.events=events;}
        @Override public TransactionStatus getTransaction(TransactionDefinition definition){assertFalse(active);active=true;events.add("begin");return new SimpleTransactionStatus();}
        @Override public void commit(TransactionStatus status){events.add("commit");active=false;if(commitFailure!=null)throw commitFailure;}
        @Override public void rollback(TransactionStatus status){events.add("rollback");active=false;}
    }
}
