package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterObservationRecorder;
import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterReads;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Two actual-PG contracts on a new P23 claim. Only inserts are attempted, and all test inserts roll back. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class OwnedPostgresqlShelterObservationTest {
    private static final String SQL_SHA="98a1b79a86c43820beba5d3a8fabeda0845e3231dfb308e88f00a4ae367d3c7f";
    private static final String CID="b1719e47b8c3ff8c334e9df96c86b4f36478eb72022b8e19278d75c9f8a7187c";
    private static final String IMAGE="sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f";
    private static final String OWNER="08b97b84-e0b5-4c6f-a5f8-3d51ec4a2f89";
    private static final String RESUME_SHA="0c471fd664f107524e395b0d414c3e1a1c107f007e1f512194f0562f97c91e47";
    private static final Path RESUME=Path.of("/private/tmp/pawbridge-main-db-integration-owned-20261021/owned-resume-receipt.json");
    private static final List<String> TABLES=List.of("pawbridge_animal.shelters","pawbridge_animal.animals","pawbridge_animal.shelter_daily_observations");
    private final Map<ch.qos.logback.classic.Logger,ch.qos.logback.classic.Level> levels=new LinkedHashMap<>();
    private Settings settings;
    private HikariDataSource pool;
    private JdbcTemplate jdbc;
    private JdbcTransactionManager manager;
    private JdbcShelterObservationRecorder recorder;
    private JdbcShelterReads reader;
    private Map<String,String> baseline;
    private boolean nextAllowed,caseEntered,completedBody;
    private int nextOrder=1;

    @BeforeAll void given__explicit_owned_claim__when__preparing__then__bind_only_the_new_p23_runtime_and_existing_recorder() {
        quiet();observed("setup",()->{
            settings=Settings.load();
            try(var input=getClass().getResourceAsStream("/owned-postgresql/shelter-observation-owned-schema.sql")) {
                check(input!=null&&SQL_SHA.equals(sha(input.readAllBytes())),"Exact frozen P23 SQL resource is mandatory");
            }
            var config=new HikariConfig();config.setPoolName("owned-p23-shelter-observation");config.setJdbcUrl(settings.url());
            config.setUsername(settings.runtime());config.setPassword(settings.password());config.setMaximumPoolSize(1);config.setMinimumIdle(0);
            config.setInitializationFailTimeout(-1);config.setConnectionTimeout(2500);config.setValidationTimeout(1000);
            config.addDataSourceProperty("connectTimeout","3");config.addDataSourceProperty("socketTimeout","5");
            pool=new HikariDataSource(config);jdbc=new JdbcTemplate(pool);jdbc.setQueryTimeout(5);manager=new JdbcTransactionManager(pool);
            // Actual Boot bean construction, without creating or starting its scheduler.
            recorder=new ShelterObservationConfiguration().shelterObservationRecorder(pool,manager);
            reader=new JdbcShelterReads(pool,manager,new ObjectMapper());
            verifyTargetAndRights();
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.shelters",Integer.class)==3
                    &&jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.animals",Integer.class)==6
                    &&jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.shelter_daily_observations",Integer.class)==2,"Exact fresh representative seed is required");
            check(today().equals(jdbc.queryForObject("SELECT max(observation_date) FROM pawbridge_animal.shelter_daily_observations",LocalDate.class)),
                    "Preparation must still be on the current database KST date; do not refresh or mutate a stale claim");
            baseline=fingerprints();released();nextAllowed=true;
        });
    }

    @BeforeEach void given__successful_previous_case__when__starting_next__then__require_full_ordered_class(TestInfo info) {
        boolean previous=nextAllowed;nextAllowed=false;caseEntered=false;completedBody=false;
        check(previous&&info.getTestMethod().orElseThrow().getAnnotation(Order.class).value()==nextOrder,"Full ordered P23 class and previous success are mandatory");
        released();caseEntered=true;
    }
    @AfterEach void given__case_finished__when__auditing__then__restore_exact_seed_by_rollback_and_return_connection() {
        if(!caseEntered)return;
        observed("after-case",()->{released();check(baseline.equals(fingerprints()),"All three source tables must match the original seed after rollback");
            released();check(completedBody,"A failed P23 case fences later work");nextOrder++;nextAllowed=true;});
    }
    @AfterAll void given__synchronous_work_settled__when__closing__then__close_only_the_idle_owned_pool_without_deleting_rows() {
        try {
            released();if(pool!=null){pool.close();check(pool.isClosed(),"The P23 pool must close normally");}
            check(nextOrder==3&&nextAllowed,"Exactly both P23 contracts must complete");
        } finally {levels.forEach(ch.qos.logback.classic.Logger::setLevel);}
    }

    @Test @Order(1)
    void given__empty_mixed_and_preobserved_shelters__when__recording_twice_in_one_transaction__then__preserve_first_kst_snapshot_and_consumer_wire_and_rollback() {
        contract("snapshot-and-consumer",()->{
            LocalDate date=today();var first=observation(103,date);check(first!=null&&first.count()==17,"The prepared first observation must be present");
            check(reader.observations(101,date,date).isEmpty(),"An existing empty shelter starts with absence rather than a fabricated zero");released();
            var outer=new TransactionTemplate(manager);outer.setTimeout(30);
            outer.executeWithoutResult(status->{
                var holder=bound();long xid=jdbc.queryForObject("SELECT txid_current()",Long.class);int pid=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
                OffsetDateTime before=jdbc.queryForObject("SELECT statement_timestamp()",OffsetDateTime.class);
                check(recorder.recordToday()==2,"Only shelters without a first observation today may be inserted");
                OffsetDateTime after=jdbc.queryForObject("SELECT statement_timestamp()",OffsetDateTime.class);
                check(bound()==holder&&jdbc.queryForObject("SELECT txid_current()",Long.class)==xid
                        &&jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class)==pid,"Recorder must join the same actual writer transaction and connection");
                var empty=observation(101,date);var mixed=observation(102,date);
                check(empty!=null&&empty.count()==0&&mixed!=null&&mixed.count()==2,"LEFT JOIN must retain zero shelters and count only the two PROTECT animals");
                check(empty.at().equals(mixed.at()),"The single INSERT statement must use one statement timestamp");
                for(var row:List.of(empty,mixed))check(!row.at().isBefore(before)&&!row.at().isAfter(after)
                        &&row.date().equals(row.at().atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate()),"New date and instant must come from the same database KST statement");
                check(first.equals(observation(103,date)),"Existing today's count and observed_at must not be rewritten");
                var saved=fingerprints();check(recorder.recordToday()==0,"Second record on the same database KST day must insert nothing");
                check(saved.equals(fingerprints()),"Second record must not alter any observation or source row");
                var rows=reader.observations(102,date.minusDays(1),date);
                check(rows.size()==2&&rows.getFirst().date().equals(date.minusDays(1))&&rows.getFirst().protectedCount()==8
                        &&rows.getLast().date().equals(date)&&rows.getLast().protectedCount()==2
                        &&rows.getLast().observedAt().toInstant().equals(mixed.at().toInstant()),"Actual existing observations reader must consume sorted original fields");
                var zero=reader.observations(101,date,date);check(zero.size()==1&&zero.getFirst().protectedCount()==0,"Actual consumer must distinguish observed zero from absence");
                status.setRollbackOnly();
            });
            released();check(baseline.equals(fingerprints()),"Positive producer inserts must be physically rolled back without cleanup writes");
            var restored=reader.observations(102,date.minusDays(1),date);check(restored.size()==1&&restored.getFirst().protectedCount()==8,
                    "Standalone existing read transaction must see only the original history after rollback");
        });
    }

    @Test @Order(2)
    void given__actual_read_only_transaction_with_insert_privilege__when__same_recorder_writes__then__postgres_rejects_and_rolls_back_without_partial_rows() {
        contract("server-read-only",()->{
            var readOnly=new TransactionTemplate(manager);readOnly.setReadOnly(true);readOnly.setTimeout(30);boolean rejected=false;
            try {
                readOnly.executeWithoutResult(status->{
                    check(TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                            &&"on".equals(jdbc.queryForObject("SELECT current_setting('transaction_read_only')",String.class)),"Actual server transaction must already be read-only");
                    recorder.recordToday();
                });
            } catch(DataAccessException failure) {rejected=sqlState(failure,"25006");}
            check(rejected,"Actual PostgreSQL read-only SQLSTATE25006 is required rather than a permission or wiring failure");
            released();check(baseline.equals(fingerprints()),"Read-only rejection must leave the original three-table baseline unchanged");
        });
    }

    private LocalDate today(){return jdbc.queryForObject("SELECT (statement_timestamp() AT TIME ZONE 'Asia/Seoul')::date",LocalDate.class);}
    private record Observation(LocalDate date,OffsetDateTime at,long count) { }
    private Observation observation(long shelter,LocalDate date) {
        var rows=jdbc.query("SELECT observation_date,observed_at,protected_count FROM pawbridge_animal.shelter_daily_observations WHERE shelter_id=? AND observation_date=?",
                (row,n)->new Observation(row.getObject(1,LocalDate.class),row.getObject(2,OffsetDateTime.class),row.getLong(3)),shelter,date);
        check(rows.size()<=1,"One observation per shelter and date is mandatory");return rows.isEmpty()?null:rows.getFirst();
    }
    private ConnectionHolder bound() {
        Object current=TransactionSynchronizationManager.getResource(pool);check(current instanceof ConnectionHolder&&TransactionSynchronizationManager.isActualTransactionActive(),"Actual writer resource must be bound");
        var holder=(ConnectionHolder)current;try{check(!holder.getConnection().getAutoCommit(),"Actual writer connection must not auto-commit");}catch(SQLException failure){throw new AssertionError("Bound connection state unavailable");}return holder;
    }
    private Map<String,String> fingerprints() {
        var result=new LinkedHashMap<String,String>();
        for(String table:TABLES){String order=table.endsWith("shelter_daily_observations")?"shelter_id,observation_date":"id";
            result.put(table,jdbc.queryForObject("SELECT md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY "+order+"),'[]'::jsonb)::text) FROM "+table+" t",String.class));}
        return result;
    }
    private void released() {
        check(!TransactionSynchronizationManager.isActualTransactionActive()&&(pool==null||!TransactionSynchronizationManager.hasResource(pool)),"No P23 transaction resource may remain bound");
        if(pool!=null&&!pool.isClosed()&&pool.getHikariPoolMXBean()!=null)check(pool.getHikariPoolMXBean().getActiveConnections()==0&&pool.getHikariPoolMXBean().getThreadsAwaitingConnection()==0,"Active work remains; do not close its pool");
    }
    private void verifyTargetAndRights() {
        var identity=jdbc.queryForMap("SELECT current_database() db,current_user actor,current_setting('server_version_num') version,pg_get_userbyid(datdba) owner FROM pg_database WHERE datname=current_database()");
        check(settings.db().equals(identity.get("db"))&&settings.runtime().equals(identity.get("actor"))&&settings.owner().equals(identity.get("owner"))&&"170011".equals(identity.get("version")),"Exact new P23 database, runtime, NOLOGIN owner and PG17.11 are required");
        check(settings.marker().equals(jdbc.queryForObject("SELECT marker FROM public.pb_shelter_observation_fixture",String.class)),"Exact P23 marker is mandatory");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit FROM pg_roles WHERE rolname=current_user",Boolean.class)),"Runtime cannot inherit administrative capabilities");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit FROM pg_roles WHERE rolname=?",Boolean.class,settings.owner()))
                &&Boolean.FALSE.equals(jdbc.queryForObject("SELECT pg_has_role(current_user,?,'MEMBER')",Boolean.class,settings.owner())),"Separate NOLOGIN fixture owner must be inaccessible");
        check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_database_privilege(current_user,current_database(),'CREATE,TEMP')",Boolean.class)),"Runtime database and TEMP DDL are forbidden");
        for(String schema:List.of("public","pawbridge_animal"))check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_schema_privilege(current_user,?,'CREATE')",Boolean.class,schema)),"Runtime schema creation is forbidden");
        check(jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='pawbridge_animal'",Integer.class)==3,"Only the three selected Animal business tables may exist");
        for(String table:TABLES){
            check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,'SELECT')",Boolean.class,table)),"Selected source SELECT must be available");
            for(String right:List.of("UPDATE","DELETE","TRUNCATE","REFERENCES","TRIGGER"))check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,?)",Boolean.class,table,right)),"Runtime source mutation and destructive privileges are forbidden");
            for(String right:List.of("UPDATE","REFERENCES"))check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_any_column_privilege(current_user,?,?)",Boolean.class,table,right)),"Column grants cannot bypass immutable sources");
            if(!table.endsWith("shelter_daily_observations"))check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_any_column_privilege(current_user,?,'INSERT')",Boolean.class,table)),"Runtime cannot insert Animal or Shelter source rows");
        }
        for(String column:List.of("shelter_id","observation_date","observed_at","protected_count"))check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_column_privilege(current_user,'pawbridge_animal.shelter_daily_observations',?,'INSERT')",Boolean.class,column)),"Only the four original observation INSERT columns must be granted");
    }

    private record Settings(String url,String db,String runtime,String password,String marker) {
        String owner(){return db.replace("pb_shelter_obs_owned_","pb_shelter_obs_owner_");}
        static Settings load() throws Exception {
            check("P23_PG_SETTINGS".equals(System.getenv("PAWBRIDGE_OWNED_SETTINGS_ENV")),"Only explicit P23 selector is accepted");
            String configured=System.getenv("P23_PG_SETTINGS");check(configured!=null&&!configured.isBlank(),"P23 settings are mandatory without skip or fallback");
            Path file=Path.of(configured),expected=Path.of("/private/tmp/pawbridge-owned-pg-night-20261020/shelter-observation/shelter-observation.properties");
            check(file.equals(expected)&&file.normalize().equals(file),"Only exact fresh shelter observation settings are accepted");privateFile(file);privateFile(RESUME);
            var json=new ObjectMapper();check(RESUME_SHA.equals(sha(Files.readAllBytes(RESUME))),"Only fresh approved Root resume receipt is accepted");
            var resumed=json.readTree(Files.readAllBytes(RESUME));check(CID.equals(resumed.path("cid").asText())&&IMAGE.equals(resumed.path("image").asText())&&OWNER.equals(resumed.path("owner").asText())
                    &&"127.0.0.1".equals(resumed.path("host").asText())&&resumed.path("port").asInt()==55437&&resumed.path("ready").asBoolean()&&!resumed.path("oldTmpfsRowsReused").asBoolean()&&resumed.path("noHostMount").asBoolean(),"Fresh owned server identity must match");
            check(Files.getPosixFilePermissions(file.getParent()).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE))&&System.getProperty("user.name").equals(Files.getOwner(file.getParent()).getName()),"Exclusive current-owner claim0700 is required");
            var values=new Properties();try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){values.load(input);}
            check(values.stringPropertyNames().equals(Set.of("jdbcUrl","username","password","database","marker")),"Exactly five private settings fields are accepted");
            String db=required(values,"database"),runtime=required(values,"username"),url=required(values,"jdbcUrl"),marker=required(values,"marker");
            check(db.matches("pb_shelter_obs_owned_[0-9a-f]{12}")&&runtime.equals(db.replace("pb_shelter_obs_owned_","pb_shelter_obs_runtime_"))&&url.equals("jdbc:postgresql://127.0.0.1:55437/"+db)&&marker.matches("[0-9a-f]{32}"),"Exact nonce runtime/database/loopback/marker are mandatory");
            Path state=file.resolveSibling("state.json");privateFile(state);var claim=json.readTree(Files.readAllBytes(state));
            check(db.equals(claim.path("database").asText())&&runtime.equals(claim.path("runtime").asText())&&db.replace("pb_shelter_obs_owned_","pb_shelter_obs_owner_").equals(claim.path("fixtureOwner").asText())
                    &&marker.equals(claim.path("marker").asText())&&"shelter-observation-v1".equals(claim.path("purpose").asText())&&"schema-ready".equals(claim.path("status").asText())&&claim.path("schemaInitialized").asBoolean()
                    &&SQL_SHA.equals(claim.path("schema_sha256").asText())&&RESUME_SHA.equals(claim.path("resume_receipt_sha256").asText())&&claim.path("port").asInt()==55437
                    &&CID.equals(claim.path("containerId").asText())&&IMAGE.equals(claim.path("image").asText())&&OWNER.equals(claim.path("owner").asText()),"Fresh P23 claim must bind exact SQL/Root receipt and runtime identity");
            return new Settings(url,db,runtime,required(values,"password"),marker);
        }
        private static String required(Properties values,String name){String value=values.getProperty(name);check(value!=null&&!value.isBlank(),"Every P23 private setting is mandatory");return value;}
        @Override public String toString(){return "OwnedShelterObservationSettings[redacted]";}
    }
    private static void privateFile(Path path) throws Exception {
        check(path.isAbsolute()&&path.normalize().equals(path)&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS),"Exact regular private file is mandatory");
        for(Path current=path;current!=null;current=current.getParent())check(!Files.isSymbolicLink(current),"No private-file symlink ancestor is allowed");
        check(Files.getPosixFilePermissions(path).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))&&((Number)Files.getAttribute(path,"unix:nlink")).intValue()==1&&System.getProperty("user.name").equals(Files.getOwner(path).getName()),"Current-owner single-link0600 file is required");
    }
    private static boolean sqlState(Throwable failure,String state) {
        for(Throwable current=failure;current!=null;current=current.getCause())if(current instanceof SQLException sql&&state.equals(sql.getSQLState()))return true;return false;
    }
    private void contract(String phase,Checked work){observed(phase,()->{work.run();completedBody=true;});}
    private static void observed(String phase,Checked work){try{work.run();}catch(AssertionError failure){throw failure;}catch(Throwable failure){throw new AssertionError("Owned P23 failed: "+phase+"; type="+failure.getClass().getName());}}
    private static void check(boolean valid,String fixed){if(!valid)throw new AssertionError(fixed);}
    private interface Checked{void run() throws Exception;}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception failure){throw new AssertionError("SHA unavailable");}}
    private void quiet(){for(String name:List.of("com.zaxxer.hikari","org.springframework.jdbc","org.postgresql")){var logger=(ch.qos.logback.classic.Logger)LoggerFactory.getLogger(name);levels.put(logger,logger.getLevel());logger.setLevel(ch.qos.logback.classic.Level.OFF);}}
}
