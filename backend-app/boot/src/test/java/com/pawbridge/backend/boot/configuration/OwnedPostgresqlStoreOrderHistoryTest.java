package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(60)
class OwnedPostgresqlStoreOrderHistoryTest {
    private static final String SQL_SHA="8ce980cb07524f7e8129c7f940744ee36dcdce5a3166b4d7c179e8f509b7cdea";
    private static final Map<String,String> KEYS=Map.ofEntries(
            Map.entry("pawbridge_user.users","user_id"),Map.entry("pawbridge_user.contact_deletions","user_id"),
            Map.entry("pawbridge_store.categories","id"),Map.entry("pawbridge_store.option_groups","id"),Map.entry("pawbridge_store.option_values","id"),
            Map.entry("pawbridge_store.products","id"),Map.entry("pawbridge_store.product_skus","id"),Map.entry("pawbridge_store.sku_values","id"),
            Map.entry("pawbridge_store.wishlists","id"),Map.entry("pawbridge_store.orders","id"),Map.entry("pawbridge_store.order_items","id"),
            Map.entry("pawbridge_store.outbox","id"),Map.entry("pawbridge_store.product_search_documents","product_id"));
    private final Map<ch.qos.logback.classic.Logger,ch.qos.logback.classic.Level> levels=new LinkedHashMap<>();
    private Settings settings;private ObservedPool pool;private JdbcTemplate jdbc;private AnnotationConfigApplicationContext context;private DirectOrders orders;
    private Map<String,String> baseline;private boolean nextAllowed,completedBody;private int nextOrder=1;

    @BeforeAll void givenExactFreshP21__whenActualConfigurationStarts__thenUseOnlySelectRoleAndExistingActor() {
        observed("P21 setup",()->{
            quiet();settings=Settings.load();
            try(InputStream resource=getClass().getResourceAsStream("/owned-postgresql/store-order-history-owned-schema.sql")){
                check(resource!=null&&sha(resource.readAllBytes()).equals(SQL_SHA),"Frozen exact P21 schema is required");
            }
            var config=new HikariConfig();config.setJdbcUrl(settings.url());config.setUsername(settings.user());config.setPassword(settings.password());
            config.setPoolName("owned-p21-store-history");config.setMaximumPoolSize(2);config.setMinimumIdle(0);config.setInitializationFailTimeout(-1);
            config.setConnectionTimeout(2000);config.setValidationTimeout(1000);config.addDataSourceProperty("connectTimeout","3");config.addDataSourceProperty("socketTimeout","5");
            pool=new ObservedPool(config);jdbc=new JdbcTemplate(pool);jdbc.setQueryTimeout(5);verifyTarget();baseline=fingerprints();
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_store.orders WHERE user_id=910001",Long.class)==7,"Exact owner seed, not an earlier trial, is required");
            context=new AnnotationConfigApplicationContext();TestPropertyValues.of("pawbridge.store-direct-orders.enabled=true","pawbridge.store-carts.enabled=false").applyTo(context);
            context.registerBean("writeDataSource",HikariDataSource.class,()->pool,definition->definition.setDestroyMethodName(""));
            context.registerBean("writeJdbc",JdbcTemplate.class,()->jdbc);context.registerBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules());
            context.register(PropertyPlaceholderAutoConfiguration.class,StoreDirectOrderConfiguration.class);context.refresh();orders=context.getBean(DirectOrders.class);
            check(context.getBean("storeDirectOrderActors") instanceof com.pawbridge.backend.user.internal.storeorder.infrastructure.JdbcStoreOrderActors,
                    "Actual current User read actor must be reused");released();nextAllowed=true;
        });
    }
    @BeforeEach void givenPreviousSuccess__whenNextStarts__thenRequireFullOrderedClass(TestInfo info){boolean prior=nextAllowed;nextAllowed=false;completedBody=false;check(prior&&info.getTestMethod().orElseThrow().getAnnotation(Order.class).value()==nextOrder,"Full P21 class and previous success are mandatory");released();}
    @AfterEach void givenReadEnded__whenAudited__thenPreserveAllThirteenTables(){pool.recording=false;released();check(baseline.equals(fingerprints()),"Every source, User, stock, snapshot, document and Outbox row must remain unchanged");check(completedBody,"Failed P21 body fences later work");nextOrder++;nextAllowed=true;}
    @AfterAll void givenSettledReads__whenClosing__thenCloseContextBeforePoolAndRequireAllFiveCases(){try{poolRecordingOff();released();if(context!=null)context.close();if(pool!=null){released();pool.close();check(pool.isClosed(),"Owned SELECT-only pool must close");}check(nextOrder==6&&nextAllowed,"Exactly five complete P21 cases are required");}finally{levels.forEach(ch.qos.logback.classic.Logger::setLevel);}}

    @Test @Order(1)
    void givenOwnAndOtherOrders__whenStatusIsAbsentOrAnEnum__thenRestrictEveryResultToVerifiedOwner(){contract("owner and status",()->{
        var all=list(910001,null,request(0,20));check(all.totalElements()==7&&new HashSet<>(ids(all)).equals(Set.of(101L,102L,103L,104L,105L,106L,107L)),"Absent status includes only this owner's seven orders");
        for(String status:List.of("PENDING","PAID","CANCELLED","COMPLETED","FAILED")){
            var page=list(910001,status,request(0,20));check(page.totalElements()==(status.equals("PAID")?3:1)&&page.content().stream().allMatch(row->row.userId()==910001L&&row.status().equals(status)),"Every enum branch must preserve owner and selected status");
        }
        var other=list(910002,"PAID",request(0,20));check(ids(other).equals(List.of(201L)),"Another verified member reads only their own order");
    });}
    @Test @Order(2)
    void givenTiedCreatedTimesAndMultipleItems__whenPagedWithDynamicSort__thenPreserveParentPageAndPurchaseSnapshots(){contract("page snapshots and sort",()->{
        var request=new StoreMemberReads.Request(0,2,List.of(new StoreMemberReads.Order("totalAmount",true,false)));
        var page=list(910001,null,request);check(ids(page).equals(List.of(102L,103L))&&page.totalElements()==7&&page.size()==2&&page.number()==0,
                "CreatedAt DESC remains primary and dynamic amount sort resolves the tied parent rows");
        check(page.sort().equals(request.sort())&&page.content().stream().allMatch(row->Objects.equals(storedCreatedAt(row.orderId()),row.createdAt())),"Page metadata and static/dynamic ordering must retain the actual timestamps");
        var first=page.content().getFirst();var second=page.content().getLast();
        check(first.items().size()==2&&second.items().size()==3&&first.items().stream().map(DirectOrders.Item::price).sorted().toList().equals(List.of(100L,200L))
                &&first.items().stream().allMatch(item->item.productName().startsWith("p21 snapshot102")&&item.skuCode().startsWith("p21-snap-102")),
                "Child snapshots are complete and differ from live SKU/product values without parent fanout");
        var next=list(910001,null,new StoreMemberReads.Request(1,2,request.sort()));check(ids(next).equals(List.of(101L,104L))&&next.totalElements()==7,"Next page advances parent orders rather than item rows");
        var empty=list(910001,null,new StoreMemberReads.Request(9,2,request.sort()));check(empty.content().isEmpty()&&empty.totalElements()==7,"An empty later page preserves the owner total");
    });}
    @Test @Order(3)
    void givenMissingOrDeletionPendingCurrentMember__whenListing__thenRejectBeforeOrderSql(){contract("current actor",()->{
        for(long user:List.of(910003L,910099L)){
            pool.samples.clear();pool.recording=true;try{expect(OrderActorRejectedException.class,()->orders.list(user,null,request(0,2)));}finally{pool.recording=false;}
            check(pool.samples.stream().filter(Sample::user).count()==1&&pool.samples.stream().noneMatch(sample->!sample.user()),"Missing or deletion-pending current User must be rejected after only one actor lookup");released();
        }
    });}
    @Test @Order(4)
    void givenSmallAndLargerParentPages__whenActuallyRead__thenUseOneReadOnlyWriterConnectionAndAtMostThreeStoreQueries(){contract("RO identity and query bound",()->{
        for(int size:List.of(2,4)){
            list(910001,null,request(0,size));var samples=List.copyOf(pool.samples);
            check(samples.stream().filter(Sample::user).count()==1&&samples.stream().filter(sample->!sample.user()).count()==3
                    &&samples.stream().filter(sample->sample.type()==QueryType.PARENT).count()==1
                    &&samples.stream().filter(sample->sample.type()==QueryType.COUNT).count()==1
                    &&samples.stream().filter(sample->sample.type()==QueryType.BULK).count()==1,
                    "Each full parent page observes one User lookup, one parent page, one count and one bulk item fetch");
            Sample user=samples.stream().filter(Sample::user).findFirst().orElseThrow();
            check(user.pid()>0&&user.bound()!=null&&user.serverReadOnly()&&samples.stream().allMatch(sample->sample.transaction()&&sample.readOnly()&&sample.manualCommit()
                    &&sample.pid()==user.pid()&&sample.bound()==user.bound()),"Actual PostgreSQL RO and the same writer PID/resource must span actor, parents and all children");
        }
    });}
    @Test @Order(5)
    void givenActualOuterWriteTransaction__whenReadFacadeJoins__thenRefuseWrongModeAndReturnEveryResource(){contract("outer mode failure",()->{
        var writes=context.getBean("storeDirectOrderWrites",TransactionTemplate.class);pool.samples.clear();pool.recording=true;
        try{expect(IllegalStateException.class,()->writes.executeWithoutResult(status->orders.list(910001,null,request(0,2))));}finally{pool.recording=false;}
        check(pool.samples.isEmpty(),"Wrong outer mode must fail before User or order SQL");released();
        check(list(910001,"PENDING",request(0,2)).content().size()==1,"A fresh subsequent actual RO call must recover after the rejected outer transaction");
    });}

    private StoreMemberReads.Page<DirectOrders.OrderDetails> list(long user,String status,StoreMemberReads.Request request){pool.samples.clear();pool.recording=true;try{return orders.list(user,status,request);}finally{pool.recording=false;}}
    private static StoreMemberReads.Request request(int page,int size){return new StoreMemberReads.Request(page,size,List.of(new StoreMemberReads.Order("id",true,false)));}
    private static List<Long> ids(StoreMemberReads.Page<DirectOrders.OrderDetails> page){return page.content().stream().map(DirectOrders.OrderDetails::orderId).toList();}
    private LocalDateTime storedCreatedAt(long id){return jdbc.queryForObject("SELECT created_at FROM pawbridge_store.orders WHERE id=?",
            (row,index)->row.getTimestamp(1,Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toLocalDateTime(),id);}
    private Map<String,String> fingerprints(){var values=new TreeMap<String,String>();KEYS.forEach((table,key)->values.put(table,jdbc.queryForObject("SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY "+key+"),'')) FROM "+table+" t",String.class)));return values;}
    private void poolRecordingOff(){if(pool!=null)pool.recording=false;}
    private void released(){check(!TransactionSynchronizationManager.isActualTransactionActive()&&(pool==null||(!TransactionSynchronizationManager.hasResource(pool)&&pool.borrows.get()==0&&pool.getHikariPoolMXBean().getActiveConnections()==0)),"Owned read borrows and transactions must be returned before later work or close");}
    private void contract(String phase,Checked body){observed(phase,()->{body.run();completedBody=true;});}
    private static void observed(String phase,Checked body){try{body.run();}catch(AssertionError failure){throw failure;}catch(Throwable failure){throw new AssertionError("Owned P21 failed: "+phase+"; type="+failure.getClass().getName());}}
    private static void expect(Class<? extends Throwable> type,Checked body){try{body.run();}catch(Throwable failure){check(type.isInstance(failure),"Only the expected refusal type is accepted");return;}throw new AssertionError("Expected refusal absent");}
    private static void check(boolean condition,String fixed){if(!condition)throw new AssertionError(fixed);}
    private interface Checked{void run()throws Exception;}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception impossible){throw new AssertionError("SHA unavailable");}}
    private void quiet(){for(String name:List.of("com.zaxxer.hikari","org.hibernate","org.springframework.jdbc","org.springframework.orm.jpa","org.springframework.context")){var logger=(ch.qos.logback.classic.Logger)LoggerFactory.getLogger(name);levels.put(logger,logger.getLevel());logger.setLevel(ch.qos.logback.classic.Level.OFF);}}
    private enum QueryType{USER,PARENT,COUNT,BULK,OTHER_STORE}
    private record Sample(boolean user,QueryType type,int pid,boolean transaction,boolean readOnly,boolean manualCommit,Object bound,boolean serverReadOnly){}
    private static final class ObservedPool extends HikariDataSource{
        final AtomicInteger borrows=new AtomicInteger();final List<Sample> samples=new CopyOnWriteArrayList<>();volatile boolean recording;
        ObservedPool(HikariConfig config){super(config);}
        @Override public Connection getConnection()throws SQLException{Connection target=super.getConnection();borrows.incrementAndGet();var closed=new AtomicBoolean();int[] backend={0};
            return(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("close")){if(closed.compareAndSet(false,true))try{return call(target,method,args);}finally{borrows.decrementAndGet();}return null;}
                Object value=call(target,method,args);
                if(value instanceof PreparedStatement statement&&args!=null&&args.length>0&&args[0] instanceof String sql){
                    String normalized=sql.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");boolean user=normalized.contains("pawbridge_user.users");boolean store=normalized.contains("pawbridge_store.");
                    QueryType type=user?QueryType.USER:normalized.matches("(?s).*\\bcount\\s*\\(.*")?QueryType.COUNT:
                            normalized.contains("pawbridge_store.orders")&&normalized.contains("pawbridge_store.order_items")&&normalized.contains(" join ")?QueryType.BULK:
                            normalized.contains("pawbridge_store.orders")&&!normalized.contains("pawbridge_store.order_items")?QueryType.PARENT:QueryType.OTHER_STORE;
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(sp,sm,sa)->{
                        if(recording&&sm.getName().startsWith("execute")&&(user||store)){
                            boolean serverRO=false;
                            // One explicit observer query per User lookup; excluded from application SQL counts.
                            if(user)try(Statement probe=target.createStatement()){probe.setQueryTimeout(3);try(ResultSet row=probe.executeQuery("SELECT pg_backend_pid(),current_setting('transaction_read_only')")){check(row.next(),"Server read-only probe must return");backend[0]=row.getInt(1);serverRO="on".equals(row.getString(2));}}
                            samples.add(new Sample(user,type,backend[0],TransactionSynchronizationManager.isActualTransactionActive(),TransactionSynchronizationManager.isCurrentTransactionReadOnly(),!target.getAutoCommit(),TransactionSynchronizationManager.getResource(ObservedPool.this),serverRO));
                        }return call(statement,sm,sa);
                    });
                }return value;
            });
        }
    }
    private static Object call(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();}}
    // Exact SELECT-only target/settings guards are appended below.

    private void verifyTarget(){
        var id=jdbc.queryForMap("SELECT current_database() db,current_user actor,session_user session,current_setting('server_version_num') version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) owner");
        check(settings.db().equals(id.get("db"))&&settings.user().equals(id.get("actor"))&&settings.user().equals(id.get("session"))&&settings.owner().equals(id.get("owner"))&&"170011".equals(id.get("version")),"Only exact fresh P21 role/database/server are accepted");
        check(settings.marker().equals(jdbc.queryForObject("SELECT marker FROM public.pb_store_history_fixture",String.class)),"P21 marker must match");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=pg_roles.oid) FROM pg_roles WHERE rolname=current_user",Boolean.class)),"History runtime cannot own or inherit administrative capability");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit FROM pg_roles WHERE rolname=?",Boolean.class,settings.owner()))&&Boolean.FALSE.equals(jdbc.queryForObject("SELECT pg_has_role(current_user,?,'MEMBER')",Boolean.class,settings.owner())),"Separate owner remains inaccessible NOLOGIN");
        check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_database_privilege(current_user,current_database(),'CREATE,TEMP')",Boolean.class)),"Runtime database/TEMP DDL forbidden");
        for(String schema:List.of("public","pawbridge_store","pawbridge_user"))check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_schema_privilege(current_user,?,'USAGE') AND NOT has_schema_privilege(current_user,?,'CREATE')",Boolean.class,schema,schema)),"Runtime schema creation forbidden");
        check(jdbc.query("SELECT schemaname||'.'||tablename FROM pg_tables WHERE schemaname IN('pawbridge_store','pawbridge_user') ORDER BY 1",(row,n)->row.getString(1)).equals(KEYS.keySet().stream().sorted().toList()),"Exact original thirteen tables are mandatory");
        for(String table:KEYS.keySet())for(String right:List.of("SELECT","INSERT","UPDATE","DELETE","TRUNCATE","REFERENCES","TRIGGER")){
            check(Boolean.valueOf(right.equals("SELECT")).equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,?)",Boolean.class,table,right)),"Every history table remains SELECT-only");
            if(Set.of("INSERT","UPDATE","REFERENCES").contains(right))check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_any_column_privilege(current_user,?,?)",Boolean.class,table,right)),"No column grant may bypass read-only rights");
        }
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,'public.pb_store_history_fixture','SELECT') AND NOT has_table_privilege(current_user,'public.pb_store_history_fixture','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')",Boolean.class)),"Marker SELECT-only is mandatory");
        var sequences=jdbc.query("SELECT n.nspname||'.'||c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='pawbridge_store' AND c.relkind='S' ORDER BY 1",(row,n)->row.getString(1));check(sequences.size()==10,"Only original Store ten identities may exist");
        for(String sequence:sequences)check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_sequence_privilege(current_user,?,'USAGE,SELECT,UPDATE')",Boolean.class,sequence)),"History runtime cannot use source sequences");
        check(jdbc.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN('public','pawbridge_store','pawbridge_user') AND c.relkind IN('r','S','v') AND pg_get_userbyid(c.relowner)<>?",Long.class,settings.owner())==0,"Every new fixture object keeps the separate owner");
    }
    private record Settings(String url,String db,String user,String password,String marker){
        String owner(){return db.replace("pb_store_history_owned_","pb_store_history_owner_");}
        static Settings load()throws Exception{
            check("P21_PG_SETTINGS".equals(System.getenv("PAWBRIDGE_OWNED_SETTINGS_ENV")),"Only explicit P21 selector accepted");
            String configured=System.getenv("P21_PG_SETTINGS");check(configured!=null&&!configured.isBlank(),"P21 settings are required without skip/fallback");
            Path expected=Path.of("/private/tmp/pawbridge-owned-pg-night-20261020/store-order-history/store-order-history.properties"),file=Path.of(configured);
            check(file.equals(expected)&&file.isAbsolute()&&file.normalize().equals(file)&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(file),"Exact fresh P21 settings only");
            for(Path parent=file.getParent();parent!=null;parent=parent.getParent())check(!Files.isSymbolicLink(parent),"No symlink settings ancestor");
            check(Files.getPosixFilePermissions(file).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))&&((Number)Files.getAttribute(file,"unix:nlink")).intValue()==1&&System.getProperty("user.name").equals(Files.getOwner(file).getName())&&Files.getOwner(file).equals(Files.getOwner(file.getParent())),"Private current-owner single-link0600 required");
            check(Files.getPosixFilePermissions(file.getParent()).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE)),"Exclusive claim0700 required");
            var values=new Properties();try(InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){values.load(input);}
            check(values.stringPropertyNames().equals(Set.of("jdbcUrl","database","username","password","marker")),"Exactly five private credential fields accepted");
            String db=required(values,"database"),user=required(values,"username"),url=required(values,"jdbcUrl"),marker=required(values,"marker");
            check(db.matches("pb_store_history_owned_[0-9a-f]{12}")&&user.equals(db.replace("pb_store_history_owned_","pb_store_history_reader_"))&&url.equals("jdbc:postgresql://127.0.0.1:65309/"+db)&&marker.matches("[0-9a-f]{32}"),"Only exact nonce reader/loopback65309/marker accepted");
            Path state=file.resolveSibling("state.json");check(Files.isRegularFile(state,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(state)&&Files.getPosixFilePermissions(state).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))&&((Number)Files.getAttribute(state,"unix:nlink")).intValue()==1&&Files.getOwner(state).equals(Files.getOwner(file)),"Exact private P21 state required");
            var claim=new ObjectMapper().readTree(Files.readAllBytes(state));
            check(db.equals(claim.path("database").asText())&&user.equals(claim.path("writer").asText())&&marker.equals(claim.path("marker").asText())&&"store-order-history-v1".equals(claim.path("purpose").asText())&&SQL_SHA.equals(claim.path("schema_sha256").asText())&&claim.path("schemaInitialized").asBoolean()&&claim.path("port").asInt()==65309
                    &&"b1719e47b8c3ff8c334e9df96c86b4f36478eb72022b8e19278d75c9f8a7187c".equals(claim.path("containerId").asText())&&"sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f".equals(claim.path("image").asText())&&"08b97b84-e0b5-4c6f-a5f8-3d51ec4a2f89".equals(claim.path("owner").asText()),"P21 state must bind the exact approved container, SQL and new claim");
            return new Settings(url,db,user,required(values,"password"),marker);
        }
        private static String required(Properties values,String key){String value=values.getProperty(key);check(value!=null&&!value.isBlank(),"Every P21 setting is mandatory");return value;}
        @Override public String toString(){return "OwnedStoreHistorySettings[redacted]";}
    }
}
