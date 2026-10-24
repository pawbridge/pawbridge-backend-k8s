package com.pawbridge.backend.user.internal.login.infrastructure;

import com.pawbridge.backend.user.internal.login.application.LocalLogin;
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore;
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcLocalLoginTest {
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
    @Test
    void given__authenticated_account__when__completing__then__issue_access_first_and_create_a_session_in_one_transaction() {
        var f = new Fixture();
        var issued = new IssuedRefreshToken("synthetic-session-token", 1_209_600);
        when(f.sessions.create(7L)).thenAnswer(call -> {
            assertTrue(f.tx.active);
            f.events.add("create-session");
            return issued;
        });

        var result = f.login.complete(ACCOUNT);

        assertEquals("safe-access", result.accessToken());
        assertSame(issued, result.refreshToken());
        assertEquals("LocalLogin.CompletedLogin", result.toString());
        assertEquals(List.of("access", "begin", "create-session", "commit"), f.events);
        verify(f.tokens).access(new JwtSessionTokens.UserClaims(7, ACCOUNT.email(), ACCOUNT.name(), ACCOUNT.role(), ACCOUNT.careRegNo()));
        assertFalse(f.tx.active);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__session_write_or_commit_failure__when__completing__then__propagate_without_retry_or_success_result(boolean commit) {
        var f = new Fixture();
        RuntimeException failure = commit
                ? new TransactionSystemException("synthetic commit")
                : new DataAccessResourceFailureException("synthetic write");
        if (commit) {
            f.tx.commitFailure = failure;
            when(f.sessions.create(7L)).thenReturn(new IssuedRefreshToken("synthetic-session-token", 1));
        } else {
            when(f.sessions.create(7L)).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> f.login.complete(ACCOUNT)));

        assertEquals(List.of("access", "begin", commit ? "commit" : "rollback"), f.events);
        verify(f.sessions).create(7L);
    }

    static final class Fixture {
        final List<String> events=new ArrayList<>();final Tx tx=new Tx(events);final RecordingJdbc jdbc=new RecordingJdbc(tx,events);
        final JwtSessionTokens tokens=mock(JwtSessionTokens.class);
        final JdbcAuthSessionStore sessions=mock(JdbcAuthSessionStore.class);
        final JdbcLocalLogin login;
        Fixture(){
            when(tokens.access(any())).thenAnswer(call->{events.add("access");return "safe-access";});
            login=new JdbcLocalLogin(jdbc,new TransactionTemplate(tx),tokens,sessions);
        }
    }
    static final class RecordingJdbc extends JdbcTemplate {
        final Tx tx;
        final List<String> events;
        String password="synthetic-hash",lastSql;
        Object[] lastArgs;
        int userRows=1;
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
                    throw new AssertionError("Unexpected query");
                }
                return result;
            }catch(SQLException failure){throw new AssertionError(failure);}
        }
    }
    static final class Tx implements PlatformTransactionManager {
        final List<String> events;boolean active;RuntimeException commitFailure;Tx(List<String> events){this.events=events;}
        @Override public TransactionStatus getTransaction(TransactionDefinition definition){assertFalse(active);active=true;events.add("begin");return new SimpleTransactionStatus();}
        @Override public void commit(TransactionStatus status){events.add("commit");active=false;if(commitFailure!=null)throw commitFailure;}
        @Override public void rollback(TransactionStatus status){events.add("rollback");active=false;}
    }
}
