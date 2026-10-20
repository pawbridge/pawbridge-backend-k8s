package com.pawbridge.backend.user.internal.oauth.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.user.internal.oauth.application.GoogleLogin.GoogleUser;
import com.pawbridge.backend.user.internal.oauth.application.GoogleLoginRejectedException;
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcGoogleLoginTest {
    private static final LocalDateTime NOW=LocalDateTime.parse("2030-01-01T12:34:56.123456");
    private static final Map<String,Object> ATTRIBUTES=Map.of("email","social@example.test","name","Social","sub","provider-sub");
    private static final GoogleUser EXISTING=new GoogleUser(7,"social@example.test","Stored","ROLE_ADMIN","care","GOOGLE");

    @ParameterizedTest @ValueSource(strings={"unsupported","missing-email","empty-email"})
    void given__unsupported_provider_or_missing_email__when__loading__then__reject_before_any_account_transaction(String example) {
        var f=new Fixture();var attributes=new HashMap<>(ATTRIBUTES);
        if(example.equals("missing-email"))attributes.remove("email");if(example.equals("empty-email"))attributes.put("email","");
        assertThrows(GoogleLoginRejectedException.class,()->f.login.load(example.equals("unsupported")?"other":"google",attributes));
        assertTrue(f.events.isEmpty());assertTrue(f.jdbc.bindings.isEmpty());assertEquals(0,f.nicknames);verifyNoInteractions(f.tokens);
    }

    @Test
    void given__existing_google_user__when__loading_again__then__preserve_stored_profile_role_and_skip_local_lookup_nickname_or_update() {
        var f=new Fixture();f.jdbc.google=List.of(EXISTING);
        assertEquals(EXISTING,f.login.load("GOOGLE",ATTRIBUTES));
        assertEquals(List.of("begin","user:GOOGLE","commit","cleanup"),f.events);assertEquals(0,f.nicknames);assertTrue(f.jdbc.bindings.isEmpty());verifyNoInteractions(f.tokens);
    }

    @Test
    void given__local_account_with_same_email__when__loading_google__then__reject_without_linking_or_inserting() {
        var f=new Fixture();f.jdbc.local=List.of(EXISTING);
        assertEquals("이미 해당 이메일로 가입된 계정이 있습니다. 일반 로그인을 이용해주세요.",assertThrows(GoogleLoginRejectedException.class,()->f.login.load("google",ATTRIBUTES)).getMessage());
        assertEquals(List.of("begin","user:GOOGLE","user:LOCAL","rollback","cleanup"),f.events);assertEquals(0,f.nicknames);assertTrue(f.jdbc.bindings.isEmpty());verifyNoInteractions(f.tokens);
    }

    @Test
    void given__new_google_account__when__loading__then__insert_source_defaults_provider_id_and_one_audit_time_without_issuing_tokens() {
        var f=new Fixture();GoogleUser user=f.login.load("google",ATTRIBUTES);
        assertEquals(new GoogleUser(19,"social@example.test","Social","ROLE_USER",null,"GOOGLE"),user);
        assertEquals(List.of("begin","user:GOOGLE","user:LOCAL","nickname","insert-user","commit","cleanup"),f.events);
        Map<Integer,Object> values=f.jdbc.bindings.getFirst();
        Map.of(1,"social@example.test",2,"Social",3,"nickname1",4,"OAuth2",5,"ROLE_USER",7,"GOOGLE",8,"provider-sub",9,Timestamp.valueOf(NOW),10,Timestamp.valueOf(NOW))
                .forEach((key,value)->assertEquals(value,values.get(key)));
        assertTrue(values.containsKey(6));assertNull(values.get(6));assertEquals(List.of("UTC","UTC"),f.jdbc.calendarZones);verifyNoInteractions(f.tokens);
    }

    @Test
    void given__integrity_failure_on_both_source_attempts__when__creating__then__try_only_one_further_nickname_in_the_same_transaction_and_propagate() {
        var f=new Fixture();var failure=new DataIntegrityViolationException("synthetic unique");f.jdbc.userFailure=failure;
        assertSame(failure,assertThrows(RuntimeException.class,()->f.login.load("google",ATTRIBUTES)));
        assertEquals(2,f.nicknames);assertEquals(List.of("begin","user:GOOGLE","user:LOCAL","nickname","insert-user","nickname","insert-user","rollback","cleanup"),f.events);verifyNoInteractions(f.tokens);
        // This controls the source catch path only, not successful PostgreSQL recovery after SQLSTATE 23505.
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__new_or_existing_refresh_row__when__completing_login__then__issue_existing_claims_then_persist_in_a_separate_transaction(boolean existing) {
        var f=new Fixture();f.jdbc.refreshIds=existing?List.of(31L):List.of();
        var result=f.login.complete(EXISTING);
        assertEquals("safe-access",result.accessToken());assertEquals("safe-refresh",result.refreshToken());
        verify(f.tokens).access(new JwtSessionTokens.UserClaims(7,"social@example.test","Stored","ROLE_ADMIN","care"));
        assertEquals(List.of("access","refresh","refresh-expiration","refresh-query","begin",existing?"update-refresh":"insert-refresh","commit","cleanup"),f.events);
        Map<Integer,Object> values=f.jdbc.bindings.getFirst();assertEquals("safe-refresh",values.get(1));
        if(existing){assertEquals(Timestamp.valueOf(NOW.plusSeconds(600)),values.get(2));assertEquals(31L,values.get(3));}
        else{assertEquals(7L,values.get(2));assertEquals(Timestamp.valueOf(NOW.plusSeconds(600)),values.get(3));assertEquals(Timestamp.valueOf(NOW),values.get(4));}
        assertFalse(f.jdbc.calendarZones.isEmpty());assertTrue(f.jdbc.calendarZones.stream().allMatch("UTC"::equals));assertEquals(0,f.nicknames);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__refresh_write_or_commit_failure__when__completing_login__then__propagate_without_retry_or_success_result(boolean commit) {
        var f=new Fixture();RuntimeException failure=commit?new TransactionSystemException("synthetic commit"):new DataAccessResourceFailureException("synthetic refresh");
        if(commit)f.manager.failure=failure;else f.jdbc.refreshFailure=failure;
        assertSame(failure,assertThrows(RuntimeException.class,()->f.login.complete(EXISTING)));
        assertEquals(List.of("access","refresh","refresh-expiration","refresh-query","begin","insert-refresh",commit?"commit":"rollback","cleanup"),f.events);
        assertEquals(1,f.jdbc.writes);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__multiple_rows_where_source_requires_optional_one__when__loading_or_completing__then__do_not_choose_an_arbitrary_row(boolean refresh) {
        var f=new Fixture();if(refresh)f.jdbc.refreshIds=List.of(1L,2L);else f.jdbc.google=List.of(EXISTING,EXISTING);
        assertThrows(IncorrectResultSizeDataAccessException.class,()->{if(refresh)f.login.complete(EXISTING);else f.login.load("google",ATTRIBUTES);});
        assertEquals(0,f.jdbc.writes);assertEquals(0,f.nicknames);
    }

    private static final class Fixture {
        final List<String> events=new ArrayList<>();final RecordingJdbc jdbc=new RecordingJdbc(events);final Manager manager=new Manager(events);
        final JwtSessionTokens tokens=mock(JwtSessionTokens.class);final JdbcGoogleLogin login;int nicknames;
        Fixture(){
            when(tokens.access(any())).thenAnswer(call->{events.add("access");return "safe-access";});
            when(tokens.refresh()).thenAnswer(call->{events.add("refresh");return "safe-refresh";});
            when(tokens.refreshExpiration()).thenAnswer(call->{events.add("refresh-expiration");return 600_999L;});
            Supplier<String> nickname=()->{events.add("nickname");return "nickname"+(++nicknames);};
            login=new JdbcGoogleLogin(jdbc,new TransactionTemplate(manager),tokens,nickname,()->NOW);
        }
    }
    private static final class RecordingJdbc extends JdbcTemplate {
        final List<String> events;List<GoogleUser> google=List.of(),local=List.of();List<Long> refreshIds=List.of();
        final List<Map<Integer,Object>> bindings=new ArrayList<>();final List<String> calendarZones=new ArrayList<>();
        RuntimeException userFailure,refreshFailure;int writes;
        RecordingJdbc(List<String> events){this.events=events;}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
            var result=new ArrayList<T>();
            try {
                if(sql.startsWith("SELECT user_id")){
                    events.add("user:"+args[1]);
                    for(var user:args[1].equals("GOOGLE")?google:local){
                        var row=mock(ResultSet.class);when(row.getLong("user_id")).thenReturn(user.userId());when(row.getString("email")).thenReturn(user.email());
                        when(row.getString("name")).thenReturn(user.name());when(row.getString("role")).thenReturn(user.role());when(row.getString("care_reg_no")).thenReturn(user.careRegNo());when(row.getString("provider")).thenReturn(user.provider());result.add(mapper.mapRow(row,0));
                    }
                }else{
                    events.add("refresh-query");assertTrue(sql.contains("FROM pawbridge_user.refresh_tokens WHERE user_id = ?"));assertArrayEquals(new Object[]{7L},args);
                    for(long id:refreshIds){var row=mock(ResultSet.class);when(row.getLong("refresh_token_id")).thenReturn(id);result.add(mapper.mapRow(row,0));}
                }
                return result;
            }catch(SQLException failure){throw new IllegalStateException(failure);}
        }
        @Override public int update(PreparedStatementCreator creator,KeyHolder keys){
            writes++;events.add("insert-user");if(userFailure!=null)throw userFailure;
            bind(creator);keys.getKeyList().add(Map.of("user_id",19L));return 1;
        }
        @Override public int update(PreparedStatementCreator creator){
            writes++;int before=events.size();bind(creator);if(events.size()==before)throw new AssertionError("missing write SQL");
            if(refreshFailure!=null)throw refreshFailure;return 1;
        }
        private void bind(PreparedStatementCreator creator){
            var values=new HashMap<Integer,Object>();bindings.add(values);var connection=mock(Connection.class);var statement=mock(PreparedStatement.class);
            try {
                when(connection.prepareStatement(anyString(),any(String[].class))).thenAnswer(call->{assertTrue(((String)call.getArgument(0)).contains("INSERT INTO pawbridge_user.users"));assertArrayEquals(new String[]{"user_id"},(String[])call.getArgument(1));return statement;});
                when(connection.prepareStatement(anyString())).thenAnswer(call->{String sql=call.getArgument(0);events.add(sql.startsWith("UPDATE")?"update-refresh":"insert-refresh");return statement;});
                doAnswer(call->{values.put(call.getArgument(0),call.getArgument(1));return null;}).when(statement).setString(anyInt(),nullable(String.class));
                doAnswer(call->{values.put(call.getArgument(0),call.getArgument(1));return null;}).when(statement).setLong(anyInt(),anyLong());
                doAnswer(call->{values.put(call.getArgument(0),call.getArgument(1));calendarZones.add(((java.util.Calendar)call.getArgument(2)).getTimeZone().getID());return null;}).when(statement).setTimestamp(anyInt(),any(Timestamp.class),any(java.util.Calendar.class));
                assertSame(statement,creator.createPreparedStatement(connection));
            }catch(SQLException failure){throw new IllegalStateException(failure);}
        }
    }
    private static final class Manager extends AbstractPlatformTransactionManager {
        final List<String> events;RuntimeException failure;Manager(List<String> events){this.events=events;}
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object transaction,TransactionDefinition definition){events.add("begin");}
        protected void doCommit(DefaultTransactionStatus status){events.add("commit");if(failure!=null)throw failure;}
        protected void doRollback(DefaultTransactionStatus status){events.add("rollback");}
        protected void doCleanupAfterCompletion(Object transaction){events.add("cleanup");}
    }
}
