package com.pawbridge.backend.user.internal.oauth.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.user.internal.oauth.application.SocialLogin.SocialUser;
import com.pawbridge.backend.user.internal.oauth.application.SocialLoginRejectedException;
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

class JdbcSocialLoginTest {
    private static final LocalDateTime NOW=LocalDateTime.parse("2030-01-01T12:34:56.123456");
    private static final Map<String,Object> ATTRIBUTES=Map.of("email","social@example.test","name","Social","sub","provider-sub");
    private static final SocialUser EXISTING=new SocialUser(7,"social@example.test","Stored","ROLE_ADMIN","care","GOOGLE");

    @ParameterizedTest @ValueSource(strings={"unsupported","missing-email","empty-email"})
    void given__unsupported_provider_or_missing_email__when__loading__then__reject_before_any_account_transaction(String example) {
        var f=new Fixture();var attributes=new HashMap<>(ATTRIBUTES);
        if(example.equals("missing-email"))attributes.remove("email");if(example.equals("empty-email"))attributes.put("email","");
        assertThrows(SocialLoginRejectedException.class,()->f.login.load(example.equals("unsupported")?"other":"google",attributes));
        assertTrue(f.events.isEmpty());assertTrue(f.jdbc.bindings.isEmpty());assertEquals(0,f.nicknames);verifyNoInteractions(f.sessions);
    }

    @Test
    void given__existing_google_user__when__loading_again__then__preserve_stored_profile_role_and_skip_local_lookup_nickname_or_update() {
        var f=new Fixture();f.jdbc.google=List.of(EXISTING);
        assertEquals(EXISTING,f.login.load("GOOGLE",ATTRIBUTES));
        assertEquals(List.of("begin","user:GOOGLE","commit","cleanup"),f.events);assertEquals(0,f.nicknames);assertTrue(f.jdbc.bindings.isEmpty());verifyNoInteractions(f.sessions);
    }

    @Test
    void given__local_account_with_same_email__when__loading_google__then__reject_without_linking_or_inserting() {
        var f=new Fixture();f.jdbc.local=List.of(EXISTING);
        assertEquals("이미 해당 이메일로 가입된 계정이 있습니다. 일반 로그인을 이용해주세요.",assertThrows(SocialLoginRejectedException.class,()->f.login.load("google",ATTRIBUTES)).getMessage());
        assertEquals(List.of("begin","user:GOOGLE","user:LOCAL","rollback","cleanup"),f.events);assertEquals(0,f.nicknames);assertTrue(f.jdbc.bindings.isEmpty());verifyNoInteractions(f.sessions);
    }

    @Test
    void given__new_google_account__when__loading__then__insert_source_defaults_provider_id_and_one_audit_time_without_issuing_tokens() {
        var f=new Fixture();SocialUser user=f.login.load("google",ATTRIBUTES);
        assertEquals(new SocialUser(19,"social@example.test","Social","ROLE_USER",null,"GOOGLE"),user);
        assertEquals(List.of("begin","user:GOOGLE","user:LOCAL","nickname","insert-user","commit","cleanup"),f.events);
        Map<Integer,Object> values=f.jdbc.bindings.getFirst();
        Map.of(1,"social@example.test",2,"Social",3,"nickname1",4,"OAuth2",5,"ROLE_USER",7,"GOOGLE",8,"provider-sub",9,Timestamp.valueOf(NOW),10,Timestamp.valueOf(NOW))
                .forEach((key,value)->assertEquals(value,values.get(key)));
        assertTrue(values.containsKey(6));assertNull(values.get(6));assertEquals(List.of("UTC","UTC"),f.jdbc.calendarZones);verifyNoInteractions(f.sessions);
    }

    @Test
    void given__integrity_failure_on_both_attempts__when__creating__then__rollback_and_cleanup_each_transaction_and_propagate_without_a_third_attempt() {
        var f=new Fixture();var failure=new DataIntegrityViolationException("synthetic unique");f.jdbc.userFailure=failure;
        assertSame(failure,assertThrows(RuntimeException.class,()->f.login.load("google",ATTRIBUTES)));
        assertEquals(2,f.nicknames);assertEquals(List.of("begin","user:GOOGLE","user:LOCAL","nickname","insert-user","rollback","cleanup",
                "begin","user:GOOGLE","user:LOCAL","nickname","insert-user","rollback","cleanup"),f.events);verifyNoInteractions(f.sessions);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__first_integrity_failure__when__retrying__then__finish_rollback_before_reloading_and_reuse_a_raced_account_or_generate_one_more_nickname(boolean racedAccount) {
        var f=new Fixture();f.jdbc.userFailure=new DataIntegrityViolationException("synthetic unique");f.jdbc.userFailuresRemaining=1;
        if(racedAccount)f.jdbc.googleAfterFailure=EXISTING;
        var result=f.login.load("google",ATTRIBUTES);
        assertEquals(racedAccount?EXISTING:new SocialUser(19,"social@example.test","Social","ROLE_USER",null,"GOOGLE"),result);
        var expected=new ArrayList<>(List.of("begin","user:GOOGLE","user:LOCAL","nickname","insert-user","rollback","cleanup","begin","user:GOOGLE"));
        if(!racedAccount)expected.addAll(List.of("user:LOCAL","nickname","insert-user"));
        expected.addAll(List.of("commit","cleanup"));assertEquals(expected,f.events);
        assertEquals(racedAccount?1:2,f.nicknames);assertEquals(racedAccount?1:2,f.jdbc.writes);verifyNoInteractions(f.sessions);
    }

    @Test
    void given__existing_social_user__when__completing_login__then__create_one_session_inside_a_separate_transaction() {
        var f = new Fixture();
        var issued = new IssuedRefreshToken("synthetic-session-token", 1_209_600);
        when(f.sessions.create(7L)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            f.events.add("create-session");
            return issued;
        });

        assertSame(issued, f.login.complete(EXISTING));

        assertEquals(List.of("begin", "create-session", "commit", "cleanup"), f.events);
        verify(f.sessions).create(7L);
        verifyNoMoreInteractions(f.sessions);
        assertEquals(0, f.jdbc.writes);
        assertEquals(0, f.nicknames);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__session_write_or_commit_failure__when__completing_login__then__propagate_without_retry_or_success_result(boolean commit) {
        var f = new Fixture();
        RuntimeException failure = commit
                ? new TransactionSystemException("synthetic commit")
                : new DataAccessResourceFailureException("synthetic session");
        if (commit) {
            f.manager.failure = failure;
            when(f.sessions.create(7L)).thenReturn(new IssuedRefreshToken("synthetic-session-token", 1));
        } else {
            when(f.sessions.create(7L)).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> f.login.complete(EXISTING)));

        assertEquals(List.of("begin", commit ? "commit" : "rollback", "cleanup"), f.events);
        verify(f.sessions).create(7L);
    }

    @Test
    void given__multiple_rows_where_source_requires_optional_one__when__loading__then__do_not_choose_an_arbitrary_row() {
        var f=new Fixture();f.jdbc.google=List.of(EXISTING,EXISTING);
        assertThrows(IncorrectResultSizeDataAccessException.class,()->f.login.load("google",ATTRIBUTES));
        assertEquals(0,f.jdbc.writes);assertEquals(0,f.nicknames);
    }

    private static final Map<String,Object> NAVER_ATTRIBUTES=Map.of("resultcode","00","message","success",
            "response",Map.of("id","naver-user-id","email","social@example.test","name","Naver"));
    private static final SocialUser NAVER_EXISTING=new SocialUser(8,"social@example.test","Stored","ROLE_USER",null,"NAVER");

    @Test
    void given__new_naver_account__when__loading__then__look_up_by_provider_id_and_insert_naver_provider_and_id() {
        var f=new Fixture();
        SocialUser user=f.login.load("naver",NAVER_ATTRIBUTES);
        assertEquals(new SocialUser(19,"social@example.test","Naver","ROLE_USER",null,"NAVER"),user);
        assertEquals(List.of("begin","user-id:NAVER","user:LOCAL","user:NAVER","nickname","insert-user","commit","cleanup"),f.events);
        Map<Integer,Object> values=f.jdbc.bindings.getFirst();
        assertEquals("social@example.test",values.get(1));
        assertEquals("Naver",values.get(2));
        assertEquals("NAVER",values.get(7));
        assertEquals("naver-user-id",values.get(8));
        verifyNoInteractions(f.sessions);
    }

    @Test
    void given__existing_naver_provider_id__when__loading__then__return_stored_account_without_email_checks_or_insert() {
        var f=new Fixture();
        f.jdbc.naverById=List.of(NAVER_EXISTING);
        assertEquals(NAVER_EXISTING,f.login.load("naver",NAVER_ATTRIBUTES));
        assertEquals(List.of("begin","user-id:NAVER","commit","cleanup"),f.events);
        assertEquals(0,f.nicknames);
        assertEquals(0,f.jdbc.writes);
    }

    @Test
    void given__local_account_with_same_email__when__loading_naver__then__reject_without_linking() {
        var f=new Fixture();
        f.jdbc.local=List.of(EXISTING);
        var rejected=assertThrows(SocialLoginRejectedException.class,()->f.login.load("naver",NAVER_ATTRIBUTES));
        assertEquals("이미 해당 이메일로 가입된 계정이 있습니다. 일반 로그인을 이용해주세요.",rejected.getMessage());
        assertEquals(List.of("begin","user-id:NAVER","user:LOCAL","rollback","cleanup"),f.events);
        assertEquals(0,f.jdbc.writes);
    }

    @Test
    void given__naver_account_with_same_email_but_other_provider_id__when__loading__then__reject_instead_of_relinking_by_email() {
        var f=new Fixture();
        f.jdbc.naverByEmail=List.of(NAVER_EXISTING);
        var rejected=assertThrows(SocialLoginRejectedException.class,()->f.login.load("naver",NAVER_ATTRIBUTES));
        assertEquals("이미 다른 소셜 계정에 연결된 이메일입니다. 관리자에게 문의해주세요.",rejected.getMessage());
        assertEquals(List.of("begin","user-id:NAVER","user:LOCAL","user:NAVER","rollback","cleanup"),f.events);
        assertEquals(0,f.jdbc.writes);
        assertEquals(0,f.nicknames);
    }

    @ParameterizedTest @ValueSource(strings={"missing-response","missing-id","missing-email"})
    void given__incomplete_naver_profile__when__loading__then__reject_before_any_account_transaction(String example) {
        var f=new Fixture();
        var response=new HashMap<String,Object>(Map.of("id","naver-user-id","email","social@example.test","name","Naver"));
        if(example.equals("missing-id"))response.remove("id");
        if(example.equals("missing-email"))response.remove("email");
        Map<String,Object> attributes=example.equals("missing-response")?Map.of("resultcode","00"):Map.of("response",response);
        assertThrows(SocialLoginRejectedException.class,()->f.login.load("naver",attributes));
        assertTrue(f.events.isEmpty());
        assertEquals(0,f.nicknames);
    }

    private static final class Fixture {
        final List<String> events=new ArrayList<>();final RecordingJdbc jdbc=new RecordingJdbc(events);final Manager manager=new Manager(events);
        final JdbcAuthSessionStore sessions=mock(JdbcAuthSessionStore.class);final JdbcSocialLogin login;int nicknames;
        Fixture(){
            Supplier<String> nickname=()->{events.add("nickname");return "nickname"+(++nicknames);};
            login=new JdbcSocialLogin(jdbc,new TransactionTemplate(manager),sessions,nickname,()->NOW);
        }
    }
    private static final class RecordingJdbc extends JdbcTemplate {
        final List<String> events;List<SocialUser> google=List.of(),local=List.of(),naverById=List.of(),naverByEmail=List.of();
        final List<Map<Integer,Object>> bindings=new ArrayList<>();final List<String> calendarZones=new ArrayList<>();
        RuntimeException userFailure;int writes,userFailuresRemaining=Integer.MAX_VALUE;SocialUser googleAfterFailure;
        RecordingJdbc(List<String> events){this.events=events;}
        private static ResultSet row(SocialUser user) throws SQLException {
            var row=mock(ResultSet.class);
            when(row.getLong("user_id")).thenReturn(user.userId());
            when(row.getString("email")).thenReturn(user.email());
            when(row.getString("name")).thenReturn(user.name());
            when(row.getString("role")).thenReturn(user.role());
            when(row.getString("care_reg_no")).thenReturn(user.careRegNo());
            when(row.getString("provider")).thenReturn(user.provider());
            return row;
        }
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
            var result=new ArrayList<T>();
            try {
                if(sql.contains("provider_id = ?")){
                    events.add("user-id:"+args[0]);assertEquals("naver-user-id",args[1]);
                    for(var user:naverById)result.add(mapper.mapRow(row(user),0));
                }else if(sql.startsWith("SELECT user_id")){
                    events.add("user:"+args[1]);
                    for(var user:switch((String)args[1]){case "GOOGLE"->google;case "NAVER"->naverByEmail;default->local;}){
                        var row=mock(ResultSet.class);when(row.getLong("user_id")).thenReturn(user.userId());when(row.getString("email")).thenReturn(user.email());
                        when(row.getString("name")).thenReturn(user.name());when(row.getString("role")).thenReturn(user.role());when(row.getString("care_reg_no")).thenReturn(user.careRegNo());when(row.getString("provider")).thenReturn(user.provider());result.add(mapper.mapRow(row,0));
                    }
                }else{
                    throw new AssertionError("Unexpected query");
                }
                return result;
            }catch(SQLException failure){throw new IllegalStateException(failure);}
        }
        @Override public int update(PreparedStatementCreator creator,KeyHolder keys){
            writes++;events.add("insert-user");if(userFailure!=null && userFailuresRemaining-->0){if(googleAfterFailure!=null)google=List.of(googleAfterFailure);throw userFailure;}
            bind(creator);keys.getKeyList().add(Map.of("user_id",19L));return 1;
        }
        private void bind(PreparedStatementCreator creator){
            var values=new HashMap<Integer,Object>();bindings.add(values);var connection=mock(Connection.class);var statement=mock(PreparedStatement.class);
            try {
                when(connection.prepareStatement(anyString(),any(String[].class))).thenAnswer(call->{assertTrue(((String)call.getArgument(0)).contains("INSERT INTO pawbridge_user.users"));assertArrayEquals(new String[]{"user_id"},(String[])call.getArgument(1));return statement;});
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
