package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.PostgresqlSearchProjector;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery;
import com.pawbridge.backend.animal.internal.discovery.application.SearchProjectionPendingException;
import com.pawbridge.backend.animal.internal.discovery.infrastructure.JdbcAnimalDiscovery;
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Exact new P20 only; one ordered class, no DDL/seed/reset, real existing search and analyzer. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(60)
class OwnedPostgresqlAnimalSearchProjectionTest {
    private static final String FIXTURE_SHA="be0bd49960e0dd85cf0ef1f25dc77704b5d61563bad9a5c989db7e4930875eae";
    private static final Map<String,String> KEYS=Map.of("animals","id","shelters","id","outbox_events","id",
            "animal_search_documents","animal_id","shelter_search_documents","shelter_id");
    private static final Set<String> SOURCE=Set.of("animals","shelters");
    private static final Set<String> DOCS=Set.of("animal_search_documents","shelter_search_documents");
    private final Map<ch.qos.logback.classic.Logger,ch.qos.logback.classic.Level> levels=new LinkedHashMap<>();
    private Settings writer,failure;
    private ObservedPool pool,failurePool;
    private JdbcTemplate jdbc,failureJdbc;
    private JdbcTransactionManager manager;
    private TransactionTemplate writes;
    private KoreanSearchAnalyzer analyzer;
    private PostgresqlSearchProjector projector,deniedProjector;
    private JdbcAnimalDiscovery discovery;
    private DriverManagerDataSource direct;
    private String immutableSource,outbox;
    private boolean nextAllowed,bodyCompleted,unsettled;
    private int nextOrder=1;

    @BeforeAll void givenExactFreshP20__whenPrepared__thenGuardIdentityAclSeedAndExistingContractsBeforeMaintenance() {
        observed("P20 preparation",()->{
            quiet(); writer=Settings.load(false); failure=Settings.load(true);
            check(writer.url().equals(failure.url()) && writer.database().equals(failure.database()) && writer.marker().equals(failure.marker()),
                    "Both minimal roles must belong to the same new P20 target and marker");
            try(InputStream source=getClass().getResourceAsStream("/owned-postgresql/animal-search-projection-owned-schema.sql")) {
                check(source!=null && sha(source.readAllBytes()).equals(FIXTURE_SHA),"Exact frozen P20 SQL binding is mandatory");
            }
            pool=pool(writer,2,"owned-p20-projection"); failurePool=pool(failure,1,"owned-p20-projection-denied");
            jdbc=new JdbcTemplate(pool);jdbc.setQueryTimeout(5);failureJdbc=new JdbcTemplate(failurePool);failureJdbc.setQueryTimeout(5);
            verifyTarget(jdbc,writer,false); verifyTarget(failureJdbc,failure,true);
            check(count("animals")==6 && count("shelters")==6 && count("outbox_events")==1
                    && count("animal_search_documents")==4 && count("shelter_search_documents")==4,
                    "P20 begins only from its exact initial source, incomplete documents and sentinel");
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.animals WHERE search_dirty",Long.class)==1
                    && jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.shelters WHERE search_dirty",Long.class)==1,
                    "Only the two initial dirty rows may exist before the first contract");
            immutableSource=sourceFingerprint();outbox=fingerprint("outbox_events");
            manager=new JdbcTransactionManager(pool);writes=new TransactionTemplate(manager);writes.setTimeout(5);
            analyzer=new KoreanSearchAnalyzer();projector=new PostgresqlSearchProjector(jdbc,writes,analyzer);
            var deniedWrites=new TransactionTemplate(new JdbcTransactionManager(failurePool));deniedWrites.setTimeout(5);
            deniedProjector=new PostgresqlSearchProjector(failureJdbc,deniedWrites,analyzer);
            discovery=new JdbcAnimalDiscovery(pool,manager,analyzer);
            direct=new DriverManagerDataSource(writer.url(),writer.username(),writer.password());
            var timeouts=new Properties();timeouts.setProperty("connectTimeout","3");timeouts.setProperty("socketTimeout","5");direct.setConnectionProperties(timeouts);
            released();nextAllowed=true;
        });
    }
    @BeforeEach void givenPriorFullContract__whenNextStarts__thenFencePartialSelectionAndEarlierFailure(TestInfo info) {
        boolean prior=nextAllowed;nextAllowed=false;bodyCompleted=false;
        observed("P20 prior contract",()->{check(prior&&!unsettled&&info.getTestMethod().orElseThrow().getAnnotation(Order.class).value()==nextOrder,
                "Only the full ordered P20 class may proceed after previous success");released();});
    }
    @AfterEach void givenCaseEnded__whenAudited__thenReleaseResourcesAndPreserveSourceAndOutbox() {
        nextAllowed=false;
        observed("P20 case cleanup",()->{
            released();check(immutableSource.equals(sourceFingerprint()) && outbox.equals(fingerprint("outbox_events")),
                    "Maintenance may change dirty flags/documents but no source content, revision, URL, status or Outbox sentinel");
            check(writer.marker().equals(jdbc.queryForObject("SELECT marker FROM public.pb_animal_projection_fixture",String.class)),"Marker remains unchanged");
            released();check(bodyCompleted,"A failed body fences every later P20 write");nextOrder++;nextAllowed=true;
        });
    }
    @AfterAll void givenSettledClass__whenClosing__thenCloseOwnedResourcesAndRequireAllFiveContracts() {
        try {
            observed("P20 final cleanup",()->{
                check(!unsettled,"Unknown work settlement forbids false cleanup");released();
                if(analyzer!=null)analyzer.close();
                if(failurePool!=null){failurePool.close();check(failurePool.isClosed(),"Failure-role pool must close");}
                if(pool!=null){pool.close();check(pool.isClosed(),"Writer pool must close");}
                System.out.println("ANIMAL_PROJECTION_OWNED completedContracts="+(nextOrder-1)+" fullClass="+(nextOrder==6&&nextAllowed)+" historyRetained=true");
                check(nextOrder==6&&nextAllowed,"Exactly five complete non-skipped contracts are required; prefix selection is not success");
            });
        }finally{if(!unsettled)levels.forEach(ch.qos.logback.classic.Logger::setLevel);}
    }

    @Test @Order(1)
    void givenDirtyAnimalAndShelter__whenBothTypesAreRepaired__thenExistingKeywordSearchRecovers() {
        contract("dirty search recovery",()->{
            expect(SearchProjectionPendingException.class,()->search(1,"말티즈"));
            check(projector.refreshDirtyShelters(1)==1,"The first bounded Shelter page repairs its dirty source");
            expect(SearchProjectionPendingException.class,()->search(1,"말티즈"));
            check(projector.refreshDirtyAnimals(1)==1,"Animal repair remains a separate bounded transaction");
            requireSearch(1,"말티즈");requireCurrent("animals",1);requireCurrent("shelters",1);
        });
    }
    @Test @Order(2)
    void givenCleanMissingRevisionAndAnalyzerMismatch__whenAuditedOneRowPerPage__thenRepairEveryClassWithoutTreatingDirtyZeroAsCompletion() {
        contract("bounded clean audit",()->{
            check(projector.refreshDirtyAnimals(1)==0 && projector.refreshDirtyShelters(1)==0,
                    "A dirty-only zero does not repair clean missing or inconsistent documents");
            check(search(2,"진돗개").content().isEmpty(),"The existing clean-missing path may return no match rather than dirty503");
            check(projector.refreshShelters(1)==1 && projector.refreshAnimals(1)==1,"The first audit page repairs only the missing row per type");
            requireCurrent("animals",2);requireCurrent("shelters",2);requireSearch(2,"진돗개");
            check(jdbc.queryForObject("SELECT source_revision FROM pawbridge_animal.animal_search_documents WHERE animal_id=3",Long.class)==0
                    && "p20-legacy".equals(jdbc.queryForObject("SELECT analyzer_version FROM pawbridge_animal.animal_search_documents WHERE animal_id=4",String.class))
                    && jdbc.queryForObject("SELECT source_revision FROM pawbridge_animal.shelter_search_documents WHERE shelter_id=3",Long.class)==0
                    && "p20-legacy".equals(jdbc.queryForObject("SELECT analyzer_version FROM pawbridge_animal.shelter_search_documents WHERE shelter_id=4",String.class)),
                    "One page cannot silently fix later revision or analyzer mismatches");
            for(int id:List.of(3,4)){
                check(projector.refreshShelters(1)==1 && projector.refreshAnimals(1)==1,"Each later audit is another bounded one-row page");
                requireCurrent("animals",id);requireCurrent("shelters",id);
            }
            check(projector.refreshAnimals(1)==0 && projector.refreshShelters(1)==0,"The current unlocked fixture has no further audit rows; this is not an operational corpus-complete claim");
        });
    }
    @Test @Order(3)
    void givenAnotherPhysicalSessionLocksDirtySource__whenSkipLockedReturnsZero__thenPendingRemainsUntilUnlockAndRetry() {
        contract("physical source locks",()->{
            for(String table:List.of("animals","shelters")){
                jdbc.update("UPDATE pawbridge_animal."+table+" SET search_dirty=true WHERE id=5");
                try(Connection holder=direct.getConnection()){
                    check(pid(holder)!=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class),"The lock holder and projector must have distinct real PostgreSQL PIDs");
                    holder.setAutoCommit(false);
                    try(PreparedStatement lock=holder.prepareStatement("SELECT id FROM pawbridge_animal."+table+" WHERE id=5 FOR UPDATE")){
                        lock.setQueryTimeout(3);try(ResultSet row=lock.executeQuery()){check(row.next(),"The exact owned source row must be locked");}
                        int repaired=table.equals("animals")?projector.refreshDirtyAnimals(1):projector.refreshDirtyShelters(1);
                        check(repaired==0 && Boolean.TRUE.equals(jdbc.queryForObject("SELECT search_dirty FROM pawbridge_animal."+table+" WHERE id=5",Boolean.class)),
                                "SKIP LOCKED zero leaves a known pending source and is not completion");
                        expect(SearchProjectionPendingException.class,()->search(5,"비글"));released();
                    }finally{holder.rollback();}
                }
                check((table.equals("animals")?projector.refreshDirtyAnimals(1):projector.refreshDirtyShelters(1))==1,"Unlock permits the next bounded retry");
                requireCurrent(table,5);requireSearch(5,"비글");
            }
        });
    }
    @Test @Order(4)
    void givenRoleWithoutDocumentWrite__whenRepairFails__thenRollbackLeavesDirtyAndDocumentsUnchangedAndWriterRetryRecovers() {
        contract("permission rollback and recovery",()->{
            for(String table:List.of("shelters","animals")){
                jdbc.update("UPDATE pawbridge_animal."+table+" SET search_dirty=true WHERE id=6");
                Map<String,String> before=allFingerprint();int rollback=failurePool.rollbacks.get();
                try{
                    if(table.equals("animals"))deniedProjector.refreshDirtyAnimals(1);else deniedProjector.refreshDirtyShelters(1);
                    throw new AssertionError("Document permission denial must be reached");
                }catch(DataAccessException denied){check("42501".equals(sqlState(denied)),"Only actual PostgreSQL insufficient-privilege is the expected failure");}
                check(failurePool.rollbacks.get()>rollback && before.equals(allFingerprint())
                        && Boolean.TRUE.equals(jdbc.queryForObject("SELECT search_dirty FROM pawbridge_animal."+table+" WHERE id=6",Boolean.class)),
                        "The failed real page must roll back, preserve all documents and leave dirty for retry");
                check((table.equals("animals")?projector.refreshDirtyAnimals(1):projector.refreshDirtyShelters(1))==1,"The original writer can recover the failed page without privilege changes");
                requireCurrent(table,6);
            }
            requireSearch(6,"치와와");
        });
    }
    @Test @Order(5)
    void givenActualOuterTransaction__whenAnyRefreshIsCalled__thenRejectBeforeSqlAndPreserveEveryTable() {
        contract("outer transaction refusal",()->{
            Map<String,String> before=allFingerprint();
            writes.executeWithoutResult(status->{
                check(TransactionSynchronizationManager.isActualTransactionActive(),"The test must enter an actual writer transaction");
                int statements=pool.statements.get();
                expect(IllegalStateException.class,()->projector.refreshDirtyAnimals(1));
                expect(IllegalStateException.class,()->projector.refreshDirtyShelters(1));
                expect(IllegalStateException.class,()->projector.refreshAnimals(1));
                expect(IllegalStateException.class,()->projector.refreshShelters(1));
                check(pool.statements.get()==statements,"Every actual outer-TX refusal must happen before SQL");
            });
            check(before.equals(allFingerprint()),"Outer-TX refusal must preserve source, documents and Outbox");
        });
    }

    private AnimalDiscovery.Page search(int id,String keyword){
        var request=new AnimalDiscovery.Search(null,null,null,null,null,null,null,null,null,keyword,"p20-"+id,null,null,null);
        return discovery.search(request,new AnimalDiscovery.PageRequest(0,10,List.of(new AnimalDiscovery.Order("createdAt",false)),true));
    }
    private void requireSearch(int id,String keyword){var result=search(id,keyword);check(result.totalElements()==1 && result.content().stream().map(AnimalDiscovery.Summary::id).toList().equals(List.of((long)id)),"The actual existing keyword search must return exactly the repaired Animal");}
    private void requireCurrent(String table,int id){String doc=table.equals("animals")?"animal_search_documents":"shelter_search_documents";String fk=table.equals("animals")?"animal_id":"shelter_id";check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT s.search_dirty AND d.source_revision=s.search_revision AND d.analyzer_version=? FROM pawbridge_animal."+table+" s JOIN pawbridge_animal."+doc+" d ON d."+fk+"=s.id WHERE s.id=?",Boolean.class,KoreanSearchAnalyzer.VERSION,id)),"Source dirty, revision and analyzer metadata must be current");}
    private long count(String table){check(KEYS.containsKey(table),"Only fixed P20 tables are counted");return jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal."+table,Long.class);}
    private String fingerprint(String table){check(KEYS.containsKey(table),"Only fixed P20 tables are fingerprinted");return jdbc.queryForObject("SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY "+KEYS.get(table)+"),'')) FROM pawbridge_animal."+table+" t",String.class);}
    private Map<String,String> allFingerprint(){var result=new LinkedHashMap<String,String>();KEYS.keySet().stream().sorted().forEach(table->result.put(table,fingerprint(table)));return result;}
    private String sourceFingerprint(){StringBuilder result=new StringBuilder();for(String table:List.of("animals","shelters"))result.append(jdbc.queryForObject("SELECT count(*)::text||':'||md5(COALESCE(string_agg((to_jsonb(t)-'search_dirty')::text,',' ORDER BY id),'')) FROM pawbridge_animal."+table+" t",String.class)).append('|');return result.toString();}
    private void released(){check(!unsettled,"Unsettled resources fence later access");boolean ok=!TransactionSynchronizationManager.isActualTransactionActive()&&(pool==null||returned(pool))&&(failurePool==null||returned(failurePool));if(!ok)unsettled=true;check(ok,"All owned JDBC borrows and transaction resources must be returned");}
    private static boolean returned(ObservedPool source){return !TransactionSynchronizationManager.hasResource(source)&&source.borrows.get()==0&&source.getHikariPoolMXBean().getActiveConnections()==0&&source.getHikariPoolMXBean().getThreadsAwaitingConnection()==0;}
    private static int pid(Connection source)throws SQLException{try(Statement statement=source.createStatement()){statement.setQueryTimeout(3);try(ResultSet row=statement.executeQuery("SELECT pg_backend_pid()")){check(row.next(),"A real backend PID is required");return row.getInt(1);}}}
    private static String sqlState(Throwable source){for(int i=0;source!=null&&i<10;i++,source=source.getCause())if(source instanceof SQLException sql)return sql.getSQLState();return null;}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception impossible){throw new AssertionError("SHA unavailable");}}
    private void contract(String phase,Checked body){observed(phase,()->{body.run();bodyCompleted=true;});}
    private static void observed(String phase,Checked body){try{body.run();}catch(AssertionError failure){throw failure;}catch(Throwable failure){throw new AssertionError("Owned P20 failed: "+phase+"; type="+failure.getClass().getName());}}
    private static void expect(Class<? extends Throwable> type,Checked body){try{body.run();}catch(Throwable failure){check(type.isInstance(failure),"Only the exact expected refusal category is accepted");return;}throw new AssertionError("Expected refusal is absent");}
    private static void check(boolean ok,String fixed){if(!ok)throw new AssertionError(fixed);}
    private interface Checked{void run()throws Exception;}
    private void quiet(){for(String name:List.of("com.zaxxer.hikari","org.springframework.jdbc","org.springframework.transaction","com.pawbridge.backend.animal.internal.command.infrastructure.search")){var logger=(ch.qos.logback.classic.Logger)LoggerFactory.getLogger(name);levels.put(logger,logger.getLevel());logger.setLevel(ch.qos.logback.classic.Level.OFF);}}
    private static ObservedPool pool(Settings value,int capacity,String name){var c=new HikariConfig();c.setJdbcUrl(value.url());c.setUsername(value.username());c.setPassword(value.password());c.setPoolName(name);c.setMaximumPoolSize(capacity);c.setMinimumIdle(0);c.setInitializationFailTimeout(-1);c.setConnectionTimeout(2000);c.setValidationTimeout(1000);c.addDataSourceProperty("connectTimeout","3");c.addDataSourceProperty("socketTimeout","5");return new ObservedPool(c);}

    /** Narrow P18-style connection ownership counters; no SQL selector, statement logger or executor. */
    private static final class ObservedPool extends HikariDataSource{
        final AtomicInteger borrows=new AtomicInteger(),statements=new AtomicInteger(),rollbacks=new AtomicInteger();
        ObservedPool(HikariConfig config){super(config);}
        @Override public Connection getConnection()throws SQLException{
            Connection target=super.getConnection();borrows.incrementAndGet();var closed=new AtomicBoolean();
            return(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("close")){if(closed.compareAndSet(false,true))try{return call(target,method,args);}finally{borrows.decrementAndGet();}return null;}
                if(method.getName().equals("prepareStatement")||method.getName().equals("createStatement"))statements.incrementAndGet();
                Object value=call(target,method,args);if(method.getName().equals("rollback"))rollbacks.incrementAndGet();return value;
            });
        }
    }
    private static Object call(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();}}

    // Exact settings and ACL guard appended below; never follows an unrelated owned claim.

    private void verifyTarget(JdbcTemplate db,Settings value,boolean denied){
        var id=db.queryForMap("SELECT current_database() db,current_user actor,session_user session,current_setting('server_version_num') version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) owner");
        check(value.database().equals(id.get("db"))&&value.username().equals(id.get("actor"))&&value.username().equals(id.get("session"))
                &&value.owner().equals(id.get("owner"))&&"170011".equals(id.get("version")),"Only the new exact P20 PostgreSQL17.11 identity is accepted");
        check(value.marker().equals(db.queryForObject("SELECT marker FROM public.pb_animal_projection_fixture",String.class)),"The exact P20 marker is mandatory");
        check(Boolean.TRUE.equals(db.queryForObject("SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=pg_roles.oid) FROM pg_roles WHERE rolname=current_user",Boolean.class)),"Both restricted logins must lack ownership/admin membership");
        check(Boolean.TRUE.equals(db.queryForObject("SELECT NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit FROM pg_roles WHERE rolname=?",Boolean.class,value.owner()))
                &&Boolean.FALSE.equals(db.queryForObject("SELECT pg_has_role(current_user,?,'MEMBER')",Boolean.class,value.owner())),"Separate fixture owner remains NOLOGIN and inaccessible");
        check(Boolean.FALSE.equals(db.queryForObject("SELECT has_database_privilege(current_user,current_database(),'CREATE,TEMP')",Boolean.class)),"Runtime database/TEMP DDL is forbidden");
        for(String schema:List.of("public","pawbridge_animal"))check(Boolean.TRUE.equals(db.queryForObject("SELECT has_schema_privilege(current_user,?,'USAGE') AND NOT has_schema_privilege(current_user,?,'CREATE')",Boolean.class,schema,schema)),"No runtime schema creation is allowed");
        check(db.query("SELECT tablename FROM pg_tables WHERE schemaname='pawbridge_animal' ORDER BY tablename",(row,n)->row.getString(1)).equals(KEYS.keySet().stream().sorted().toList()),"Only exact source/docs4 and Outbox sentinel tables exist");
        check(db.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind IN('r','p','v','m','f')",Long.class)==6,"Only five application tables and one marker are accepted");
        for(String table:KEYS.keySet())for(String right:List.of("SELECT","INSERT","UPDATE","DELETE","TRUNCATE","REFERENCES","TRIGGER")){
            boolean allowed=right.equals("SELECT")||(!denied&&DOCS.contains(table)&&Set.of("INSERT","UPDATE").contains(right));
            check(Boolean.valueOf(allowed).equals(db.queryForObject("SELECT has_table_privilege(current_user,?,?)",Boolean.class,"pawbridge_animal."+table,right)),"Every table ACL must match the exact role boundary");
            if(!allowed&&Set.of("INSERT","UPDATE","REFERENCES").contains(right)&&!(SOURCE.contains(table)&&right.equals("UPDATE")))
                check(Boolean.FALSE.equals(db.queryForObject("SELECT has_any_column_privilege(current_user,?,?)",Boolean.class,"pawbridge_animal."+table,right)),"Column privileges cannot bypass denied operations");
        }
        for(String table:SOURCE){
            var columns=db.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema='pawbridge_animal' AND table_name=? ORDER BY ordinal_position",String.class,table);
            check(columns.contains("search_dirty"),"Source dirty column must exist");
            for(String column:columns)check(Boolean.valueOf(column.equals("search_dirty")).equals(db.queryForObject("SELECT has_column_privilege(current_user,?,?,'UPDATE')",Boolean.class,"pawbridge_animal."+table,column)),"Only the exact source dirty column may be updated");
        }
        check(Boolean.TRUE.equals(db.queryForObject("SELECT has_table_privilege(current_user,'public.pb_animal_projection_fixture','SELECT') AND NOT has_table_privilege(current_user,'public.pb_animal_projection_fixture','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')",Boolean.class)),"Marker is SELECT-only");
        check(db.query("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='pawbridge_animal' AND c.relkind='S' ORDER BY c.relname",(row,n)->row.getString(1)).equals(List.of("animals_id_seq","outbox_events_id_seq","shelters_id_seq")),"Only the three original source/sentinel identities exist");
        for(String seq:List.of("animals_id_seq","shelters_id_seq","outbox_events_id_seq"))check(Boolean.FALSE.equals(db.queryForObject("SELECT has_sequence_privilege(current_user,?,'USAGE,SELECT,UPDATE')",Boolean.class,"pawbridge_animal."+seq)),"No runtime source/sentinel identity writes are allowed");
        check(db.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN('public','pawbridge_animal') AND c.relkind IN('r','S') AND pg_get_userbyid(c.relowner)<>?",Long.class,value.owner())==0,"All fixture objects retain the separate owner");
    }
    private record Settings(String url,String database,String username,String password,String marker){
        String owner(){return database.replace("pb_animal_projection_owned_","pb_animal_projection_owner_");}
        static Settings load(boolean denied)throws Exception{
            check("P20_PG_SETTINGS".equals(System.getenv("PAWBRIDGE_OWNED_SETTINGS_ENV")),"Only the exact P20 selector is accepted");
            String configured=System.getenv("P20_PG_SETTINGS");check(configured!=null&&!configured.isBlank(),"P20 settings are required without fallback or skip");
            Path primary=Path.of("/private/tmp/pawbridge-owned-pg-night-20261020/animal-search-projection/animal-search-projection.properties");
            check(Path.of(configured).equals(primary),"Only the exact exclusive P20 claim settings are accepted");
            Path file=denied?primary.resolveSibling("failure.properties"):primary;
            check(file.isAbsolute()&&file.normalize().equals(file)&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(file),"P20 settings must be the exact private regular file");
            for(Path parent=file.getParent();parent!=null;parent=parent.getParent())check(!Files.isSymbolicLink(parent),"Settings ancestors cannot be symlinks");
            check(Files.getPosixFilePermissions(file,LinkOption.NOFOLLOW_LINKS).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))
                    &&((Number)Files.getAttribute(file,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()==1
                    &&System.getProperty("user.name").equals(Files.getOwner(file).getName())&&Files.getOwner(file).equals(Files.getOwner(file.getParent())),"P20 settings must be current-owner single-link0600");
            check(Files.getPosixFilePermissions(file.getParent()).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE)),"P20 claim directory must remain0700");
            var values=new Properties();try(InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){values.load(in);}
            check(values.stringPropertyNames().equals(Set.of("jdbcUrl","database","username","password","marker")),"Exactly the existing five credential fields are accepted");
            String db=required(values,"database"),url=required(values,"jdbcUrl"),user=required(values,"username"),marker=required(values,"marker");
            check(db.matches("pb_animal_projection_owned_[0-9a-f]{12}")&&url.equals("jdbc:postgresql://127.0.0.1:65309/"+db)
                    &&user.equals(db.replace("pb_animal_projection_owned_",denied?"pb_animal_projection_failure_":"pb_animal_projection_writer_"))&&marker.matches("[0-9a-f]{32}"),"Only the fresh exact P20 database, role, marker and resumed loopback port are accepted");
            Path state=file.resolveSibling("state.json");
            check(Files.isRegularFile(state,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(state)
                    &&Files.getPosixFilePermissions(state).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))
                    &&((Number)Files.getAttribute(state,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()==1&&Files.getOwner(state).equals(Files.getOwner(file)),"Exact P20 claim state must remain private");
            var claim=new ObjectMapper().readTree(Files.readAllBytes(state));
            check(db.equals(claim.path("database").asText())&&user.equals(claim.path(denied?"failure":"writer").asText())&&marker.equals(claim.path("marker").asText())
                    &&"animal-search-projection-v1".equals(claim.path("purpose").asText())&&FIXTURE_SHA.equals(claim.path("schema_sha256").asText())
                    &&claim.path("schemaInitialized").asBoolean()&&claim.path("port").asInt()==65309
                    &&"b1719e47b8c3ff8c334e9df96c86b4f36478eb72022b8e19278d75c9f8a7187c".equals(claim.path("containerId").asText())
                    &&"sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f".equals(claim.path("image").asText())
                    &&"08b97b84-e0b5-4c6f-a5f8-3d51ec4a2f89".equals(claim.path("owner").asText()),"Claim must bind the exact P20 nonce, purpose, SQL and approved container identity");
            return new Settings(url,db,user,required(values,"password"),marker);
        }
        private static String required(Properties values,String key){String value=values.getProperty(key);check(value!=null&&!value.isBlank(),"Every private P20 value is mandatory");return value;}
        @Override public String toString(){return "OwnedProjectionSettings[redacted]";}
    }
}
