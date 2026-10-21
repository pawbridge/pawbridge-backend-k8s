package com.pawbridge.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.user.internal.login.infrastructure.JdbcLocalLogin;
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual P24 grants only: commits one synthetic refresh row, then updates it; no Main or external service. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OwnedPostgresqlLocalLoginWriterTest {
    private static final String GRANTS_SHA="4fb2d62a1f8504ab0325f5aadf5c2c09c2890370bb9b2005e29d1a0d46af4f3a";
    private static final String CID="2c1bf9aff9dcc94051ef1f0177bcf9737d746b5cfb56e07d7c152e552d7668bb";
    private static final String IMAGE="sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f";
    private static final String OWNER="fbbe20ed-75a9-44e2-93e2-eb5f9a694c17";
    private static final int PORT=65298;
    private static final String SCHEMA_OWNER="pb_local_login_owner";
    private static final LocalDateTime NOW=LocalDateTime.of(2026,10,21,10,0);
    private static final List<String> USER_COLUMNS=List.of("user_id","care_reg_no","created_at","email","name","nickname","password","provider","provider_id","role","updated_at");
    private static final List<String> REFRESH_COLUMNS=List.of("refresh_token_id","created_at","expires_at","token","user_id");
    private final Map<ch.qos.logback.classic.Logger,ch.qos.logback.classic.Level> levels=new LinkedHashMap<>();
    private HikariDataSource writerPool,readerPool;
    private JdbcTemplate writer,reader;
    private Settings settings;

    @BeforeAll void given__explicit_fresh_p24__when__opening__then__bind_only_the_two_owned_roles() {
        observed("setup",()->{
            quiet();settings=Settings.load();
            try(var stream=getClass().getResourceAsStream("/owned-postgresql/local-login-writer-grants.sql")){
                check(stream!=null&&GRANTS_SHA.equals(sha(stream.readAllBytes())),"Exact published incremental grants resource is required");
            }
            writerPool=pool(settings.url(),settings.writer(),settings.writerPassword(),false,"p24-login-writer");
            readerPool=pool(settings.url(),settings.reader(),settings.readerPassword(),true,"p24-login-reader");
            writer=new JdbcTemplate(writerPool);reader=new JdbcTemplate(readerPool);writer.setQueryTimeout(5);reader.setQueryTimeout(5);
            target(writer,settings.writer());target(reader,settings.reader());
            check(writer.queryForObject("SELECT count(*) FROM pawbridge_user.users",Integer.class)==1
                    &&writer.queryForObject("SELECT count(*) FROM pawbridge_user.refresh_tokens",Integer.class)==0,"One original user and no refresh row are required before the single P24 run");
            released();
        });
    }

    @Test void given__baseline_auth_grants_plus_only_login_increment__when__first_login_then_relogin__then__persist_identity_without_sequence_acl_and_keep_reader_isolation() {
        observed("grant-and-login",()->{
            rights();String baseline=publicUsers();
            denied(()->reader.queryForObject("SELECT password FROM pawbridge_user.users WHERE user_id = 7",String.class));
            denied(()->reader.queryForObject("SELECT token FROM pawbridge_user.refresh_tokens",String.class));
            String sequence=writer.queryForObject("SELECT pg_get_serial_sequence('pawbridge_user.refresh_tokens','refresh_token_id')",String.class);
            check(sequence!=null&&sequence.startsWith("pawbridge_user."),"Original identity sequence metadata must be present");
            denied(()->writer.queryForObject("SELECT nextval(?::regclass)",Long.class,sequence));
            var tokens=new JwtSessionTokens(Keys.hmacShaKeyFor("synthetic-local-login-owned-key-0123456789-abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8)),600000,600999);
            var manager=new JdbcTransactionManager(writerPool);var save=new TransactionTemplate(manager);save.setTimeout(15);
            var login=new JdbcLocalLogin(writer,save,tokens,()->NOW);
            var account=login.findByEmail("SYNTHETIC@fixture.invalid").orElseThrow();
            check(account.userId()==7&&account.email().equals("synthetic@fixture.invalid")&&account.name().equals("Synthetic")
                    &&account.role().equals("ROLE_USER")&&account.careRegNo()==null,"Original casefold user query must preserve the prepared account fields");
            check(new BCryptPasswordEncoder().matches("synthetic-password",account.password()),"Writer must actually select the prepared BCrypt password");
            var first=login.complete(account);released();
            var inserted=refresh();
            check(inserted.id()>0&&inserted.userId()==7&&inserted.token().equals(first.refreshToken())
                    &&inserted.expires().equals(NOW.plusSeconds(600)),"Actual first save must commit one identity-generated refresh row with truncated expiry");
            check(tokens.valid(first.accessToken())&&tokens.valid(first.refreshToken()),"First actual JDBC save must return signed usable session tokens");
            var second=login.complete(account);released();var updated=refresh();
            check(updated.id()==inserted.id()&&updated.userId()==7&&updated.token().equals(second.refreshToken())
                    &&updated.expires().equals(inserted.expires()),"Second save must update the same row; same-second token equality is allowed");
            check(tokens.valid(second.accessToken())&&tokens.valid(second.refreshToken()),"Second actual JDBC save must return signed usable session tokens");
            check(writer.queryForObject("SELECT count(*) FROM pawbridge_user.refresh_tokens",Integer.class)==1,"Relogin must not insert another refresh row");
            denied(()->writer.update("UPDATE pawbridge_user.users SET name = 'blocked' WHERE user_id = 7"));
            denied(()->writer.update("INSERT INTO pawbridge_user.users(email,name,nickname,provider,role) VALUES ('blocked@fixture.invalid','Blocked','blocked','LOCAL','ROLE_USER')"));
            denied(()->writer.update("UPDATE pawbridge_user.refresh_tokens SET user_id = 7"));
            denied(()->writer.update("UPDATE pawbridge_user.refresh_tokens SET created_at = TIMESTAMP '2000-01-01'"));
            denied(()->writer.update("INSERT INTO pawbridge_user.refresh_tokens(refresh_token_id,token,user_id,expires_at,created_at) VALUES (999,'blocked',7,TIMESTAMP '2030-01-01',TIMESTAMP '2030-01-01')"));
            check(baseline.equals(publicUsers()),"Every original reader-visible user field must remain unchanged");
            check(account.password().equals(login.findByEmail(account.email()).orElseThrow().password()),"Login and denied writes must preserve the original password");
            rights();released();
        });
    }

    @AfterAll void given__synchronous_work_finished__when__closing__then__return_connections_and_close_only_owned_pools() {
        try{observed("release",this::released);}
        finally{
            try{if(writerPool!=null){writerPool.close();check(writerPool.isClosed(),"Writer pool must close normally");}}
            finally{try{if(readerPool!=null){readerPool.close();check(readerPool.isClosed(),"Reader pool must close normally");}}
                finally{levels.forEach(ch.qos.logback.classic.Logger::setLevel);}}
        }
    }
    private void target(JdbcTemplate jdbc,String role) {
        var row=jdbc.queryForMap("SELECT current_database() db,current_user actor,current_setting('server_version_num') version");
        check(settings.db().equals(row.get("db"))&&role.equals(row.get("actor"))&&"170011".equals(row.get("version")),"Exact P24 database role and PostgreSQL version are required");
        check(settings.marker().equals(jdbc.queryForObject("SELECT marker FROM public.pb_local_login_fixture",String.class)),"Exact owned P24 marker is mandatory");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class)),"P24 login roles cannot have administrative capabilities");
        check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT pg_has_role(current_user,?,'MEMBER')",Boolean.class,SCHEMA_OWNER)),"Runtime roles must not inherit the fixture owner");
        check(SCHEMA_OWNER.equals(jdbc.queryForObject("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname='pawbridge_user'",String.class)),"Separate fixture schema owner is required");
    }
    private void rights() {
        columns(writer,"pawbridge_user.users",USER_COLUMNS,"SELECT",Set.of("user_id","nickname","role","care_reg_no","email","name","password"));
        columns(reader,"pawbridge_user.users",USER_COLUMNS,"SELECT",Set.of("user_id","nickname","email","name","provider","role","created_at"));
        columns(writer,"pawbridge_user.refresh_tokens",REFRESH_COLUMNS,"SELECT",Set.of("refresh_token_id","token","user_id","expires_at"));
        columns(writer,"pawbridge_user.refresh_tokens",REFRESH_COLUMNS,"INSERT",Set.of("token","user_id","expires_at","created_at"));
        columns(writer,"pawbridge_user.refresh_tokens",REFRESH_COLUMNS,"UPDATE",Set.of("token","expires_at"));
        for(JdbcTemplate jdbc:List.of(writer,reader)){
            check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,'pawbridge_user.users','SELECT')",Boolean.class)),"User SELECT must remain column-scoped");
            for(String right:List.of("INSERT","UPDATE","REFERENCES"))check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_any_column_privilege(current_user,'pawbridge_user.users',?)",Boolean.class,right)),"User writes must remain unavailable");
            for(String right:List.of("DELETE","TRUNCATE","TRIGGER"))check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,'pawbridge_user.users',?)",Boolean.class,right)),"Destructive user operations must remain unavailable");
            for(String table:List.of("users","refresh_tokens")){
                String id=table.equals("users")?"user_id":"refresh_token_id";
                String sequence=jdbc.queryForObject("SELECT pg_get_serial_sequence(?,?)",String.class,"pawbridge_user."+table,id);
                check(sequence!=null,"Original V1 identity metadata is required");
                for(String right:List.of("USAGE","SELECT","UPDATE"))check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_sequence_privilege(current_user,?,?)",Boolean.class,sequence,right)),"No sequence ACL may be added for login");
            }
        }
        check(Boolean.TRUE.equals(writer.queryForObject("SELECT has_table_privilege(current_user,'pawbridge_user.refresh_tokens','DELETE')",Boolean.class)),"Existing logout DELETE grant must remain unchanged");
        check(Boolean.FALSE.equals(reader.queryForObject("SELECT has_table_privilege(current_user,'pawbridge_user.refresh_tokens','SELECT') OR has_any_column_privilege(current_user,'pawbridge_user.refresh_tokens','SELECT')",Boolean.class)),"Reader must not gain refresh token access");
        check("BY DEFAULT".equals(writer.queryForObject("SELECT identity_generation FROM information_schema.columns WHERE table_schema='pawbridge_user' AND table_name='refresh_tokens' AND column_name='refresh_token_id'",String.class)),"Original V1 BY DEFAULT identity must be retained");
    }
    private static void columns(JdbcTemplate jdbc,String table,List<String> columns,String right,Set<String> expected) {
        for(String column:columns)check(Boolean.valueOf(expected.contains(column)).equals(jdbc.queryForObject("SELECT has_column_privilege(current_user,?,?,?)",Boolean.class,table,column,right)),"Exact documented column privileges must match the login increment only");
    }
    private Refresh refresh() {
        var rows=writer.query("SELECT refresh_token_id,token,user_id,expires_at FROM pawbridge_user.refresh_tokens WHERE user_id = 7",
                (row,n)->new Refresh(row.getLong("refresh_token_id"),row.getString("token"),row.getLong("user_id"),row.getTimestamp("expires_at",Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toLocalDateTime()));
        check(rows.size()==1,"Exactly one owned refresh row must be visible after save");return rows.getFirst();
    }
    private String publicUsers(){return reader.queryForObject("SELECT md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY user_id),'[]'::jsonb)::text) FROM (SELECT user_id,nickname,email,name,provider,role,created_at FROM pawbridge_user.users) t",String.class);}
    private void released(){
        check(!TransactionSynchronizationManager.isActualTransactionActive(),"No P24 transaction may remain active");
        for(HikariDataSource pool:Arrays.asList(writerPool,readerPool))if(pool!=null&&!pool.isClosed()){
            check(!TransactionSynchronizationManager.hasResource(pool),"No P24 connection may remain bound");
            if(pool.getHikariPoolMXBean()!=null)check(pool.getHikariPoolMXBean().getActiveConnections()==0&&pool.getHikariPoolMXBean().getThreadsAwaitingConnection()==0,"Owned pools must be idle before close");
        }
    }
    private static HikariDataSource pool(String url,String username,String password,boolean readOnly,String name){
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername(username);config.setPassword(password);config.setPoolName(name);
        config.setMaximumPoolSize(1);config.setMinimumIdle(0);config.setConnectionTimeout(2000);config.setValidationTimeout(1000);config.setInitializationFailTimeout(-1);config.setReadOnly(readOnly);return new HikariDataSource(config);
    }
    private record Refresh(long id,String token,long userId,LocalDateTime expires){@Override public String toString(){return "P24Refresh[redacted]";}}
    private record Settings(String url,String db,String marker,String writer,String writerPassword,String reader,String readerPassword){
        static Settings load()throws Exception{
            check("P24_PG_SETTINGS".equals(System.getenv("PAWBRIDGE_OWNED_SETTINGS_ENV")),"Explicit P24 selector is required without fallback");
            Path file=Path.of("/private/tmp/pawbridge-local-login-owned-20261021/login.properties");
            check(file.toString().equals(System.getenv("P24_PG_SETTINGS")),"Only the exact fresh P24 private settings path is accepted");privateFile(file);
            check(Files.getPosixFilePermissions(file.getParent()).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE)),"P24 private directory0700 is required");
            var values=new Properties();try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){values.load(input);}
            check(values.stringPropertyNames().equals(Set.of("jdbcUrl","database","marker","writerUsername","writerPassword","readerUsername","readerPassword")),"Exactly seven P24 settings fields are required");
            String db=required(values,"database"),url=required(values,"jdbcUrl"),marker=required(values,"marker"),writer=required(values,"writerUsername"),reader=required(values,"readerUsername");
            check(db.matches("pb_local_login_owned_[0-9a-f]{12}")&&url.equals("jdbc:postgresql://127.0.0.1:"+PORT+"/"+db)&&marker.matches("[0-9a-f]{40}")&&writer.equals("pawbridge_f3_writer")&&reader.equals("pawbridge_f2_reader"),"Exact fresh loopback database and documented two roles are required");
            Path state=file.resolveSibling("state.json");privateFile(state);var claim=new ObjectMapper().readTree(Files.readAllBytes(state));
            check(CID.equals(claim.path("containerId").asText())&&IMAGE.equals(claim.path("image").asText())&&OWNER.equals(claim.path("owner").asText())&&claim.path("port").asInt()==PORT&&claim.path("ready").asBoolean(),"Root's exact ready owned server identity must match");
            check("local-login-writer-v1".equals(claim.path("purpose").asText())&&GRANTS_SHA.equals(claim.path("grants_sha256").asText()),"Root preparation must bind the exact login grants increment");
            return new Settings(url,db,marker,writer,required(values,"writerPassword"),reader,required(values,"readerPassword"));
        }
        private static String required(Properties values,String key){String value=values.getProperty(key);check(value!=null&&!value.isBlank(),"Every P24 private field is mandatory");return value;}
        @Override public String toString(){return "P24Settings[redacted]";}
    }
    private static void privateFile(Path file)throws Exception{
        check(file.isAbsolute()&&file.normalize().equals(file)&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(file),"Regular normalized private P24 file is required");
        check(Files.getPosixFilePermissions(file).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))&&((Number)Files.getAttribute(file,"unix:nlink")).intValue()==1&&System.getProperty("user.name").equals(Files.getOwner(file).getName()),"P24 inputs must be current-owner single-link0600");
    }
    private static void denied(Runnable attempt){
        try{attempt.run();throw new AssertionError("The actual PostgreSQL grant must reject this operation");}
        catch(DataAccessException failure){boolean denied=false;for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql&&"42501".equals(sql.getSQLState()))denied=true;check(denied,"Only actual SQLSTATE42501 proves the missing privilege");}
    }
    private static void check(boolean valid,String fixed){if(!valid)throw new AssertionError(fixed);}
    private static void observed(String phase,Checked work){try{work.run();}catch(AssertionError failure){throw failure;}catch(Throwable failure){throw new AssertionError("Owned P24 failed: "+phase+"; type="+failure.getClass().getName());}}
    private interface Checked{void run()throws Exception;}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private void quiet(){for(String name:List.of("com.zaxxer.hikari","org.springframework.jdbc","org.postgresql")){var logger=(ch.qos.logback.classic.Logger)LoggerFactory.getLogger(name);levels.put(logger,logger.getLevel());logger.setLevel(ch.qos.logback.classic.Level.OFF);}}
}
