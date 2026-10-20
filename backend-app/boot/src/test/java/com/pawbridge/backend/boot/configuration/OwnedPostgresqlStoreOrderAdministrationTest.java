package com.pawbridge.backend.boot.configuration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.store.api.StoreOrderActors;
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManagerFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Candidate secured administrator HTTP/JPA on a fresh P13 database; no payment or original JPA oracle execution. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class OwnedPostgresqlStoreOrderAdministrationTest {
    private static final String ROOT="/api/v1/admin/orders";
    private static final long ADMIN_A=910101,ADMIN_B=910102,USER=910001,SHELTER=910103,PENDING=910104,MISSING=910199;
    private static final long ORDER_A=910010,ORDER_B=910011;
    private static final Map<String, String> TABLE_KEYS = Map.ofEntries(
            Map.entry("pawbridge_user.users","user_id"), Map.entry("pawbridge_user.contact_deletions","user_id"),
            Map.entry("pawbridge_store.categories","id"), Map.entry("pawbridge_store.option_groups","id"),
            Map.entry("pawbridge_store.option_values","id"), Map.entry("pawbridge_store.products","id"),
            Map.entry("pawbridge_store.product_skus","id"), Map.entry("pawbridge_store.sku_values","id"),
            Map.entry("pawbridge_store.wishlists","id"), Map.entry("pawbridge_store.orders","id"),
            Map.entry("pawbridge_store.order_items","id"), Map.entry("pawbridge_store.outbox","id"),
            Map.entry("pawbridge_store.product_search_documents","product_id"),
            Map.entry("pawbridge_store.carts","id"),Map.entry("pawbridge_store.cart_items","id"));
    private static final SecretKey KEY=Keys.hmacShaKeyFor("owned-p13-public-synthetic-signing-key-20261021-only".getBytes(StandardCharsets.UTF_8));
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private Settings settings;
    private HikariDataSource pool;
    private DriverManagerDataSource probe;
    private JdbcTemplate jdbc;
    private Map<String,Fingerprint> baseline;
    private final Map<Long,Map<String,Object>> ownedOrders=new LinkedHashMap<>();
    private boolean unresolvedWorkers;

    @BeforeAll
    void verify__the_new_owned_admin_fixture_before_application_connections() {
        observed("owned admin identity",()->{
            settings=Settings.load();probe=new DriverManagerDataSource(settings.jdbcUrl(),settings.username(),settings.password());
            Properties timeout=new Properties();timeout.setProperty("connectTimeout","3");timeout.setProperty("socketTimeout","5");probe.setConnectionProperties(timeout);
            verifyServer(new JdbcTemplate(probe),settings);
            pool=newPool("owned-admin-order-writer");jdbc=new JdbcTemplate(pool);jdbc.setQueryTimeout(5);
            for(String table:TABLE_KEYS.keySet())if(!table.equals("pawbridge_user.users")&&!table.equals("pawbridge_store.orders"))
                check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,'INSERT,UPDATE,DELETE')",Boolean.class,table)),"Admin writer must not mutate stock, items, events, Cart or deletion state");
            check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,'pawbridge_store.orders','UPDATE') AND NOT has_table_privilege(current_user,'pawbridge_store.orders','INSERT,DELETE') AND NOT has_column_privilege(current_user,'pawbridge_user.users','role','UPDATE')",Boolean.class)),
                    "Only Order UPDATE and the separate User PK lock permission may write");
            for(long id:List.of(ORDER_A,ORDER_B)) {
                Map<String,Object> row=jdbc.queryForMap("SELECT * FROM pawbridge_store.orders WHERE id=?",id);
                check(((Number)row.get("user_id")).longValue()==USER&&row.get("delivery_message").equals(id==ORDER_A?"OWNED-P13-A":"OWNED-P13-B"),"Mutable fixture orders must carry their exact pre-owned identity");
                ownedOrders.put(id,row);
            }
            baseline=fingerprint();released(pool);
        });
    }
    @BeforeEach void require__the_full_foreign_and_owned_baseline() {
        check(!unresolvedWorkers&&fingerprint().equals(baseline),"Each case requires all fifteen tables at the original baseline");
    }
    @AfterEach void restore__only_the_pre_registered_order_delivery_columns() {
        observed("owned admin order restoration",()->{
            check(!unresolvedWorkers,"Unsettled workers forbid fixture restoration");released(pool);
            for(var entry:ownedOrders.entrySet()) {
                long id=entry.getKey();Map<String,Object> row=entry.getValue();
                check(id==ORDER_A||id==ORDER_B,"No foreign order may enter cleanup");
                int changed=jdbc.update("UPDATE pawbridge_store.orders SET delivery_status=?,updated_at=? WHERE id=? AND user_id=? AND delivery_message=?",
                        row.get("delivery_status"),row.get("updated_at"),id,USER,row.get("delivery_message"));
                check(changed==1,"Owned order identity must remain intact before delivery restoration");
            }
            check(fingerprint().equals(baseline),"All fifteen tables, including every foreign row and all non-delivery Order columns, must return to baseline");released(pool);
        });
    }
    @AfterAll void close__only_the_owned_application_pool() {
        check(!unresolvedWorkers,"Unsettled requests forbid pool close");if(pool!=null){pool.close();check(pool.isClosed(),"Owned admin writer pool must close");}
    }

    @Test
    void givenCurrentAdministratorsAndOtherDbRoles_whenListingReadingAndUpdatingDelivery_thenPreservePageWireAndRejectStaleOrForgedAuthority() {
        http(pool,(mvc,context)->{
            JsonNode first=send(mvc,"GET",ROOT+"?userId=910001&sortBy=id&sortOrder=asc&page=0&size=1",token(ADMIN_A),null,200);
            check(first.path("content").size()==1&&first.path("content").get(0).path("orderId").asLong()==ORDER_A
                    &&first.path("number").asInt()==0&&first.path("size").asInt()==1&&first.path("totalElements").asLong()==2,
                    "Admin listing must retain filtered Page metadata and requested scalar order");
            JsonNode next=send(mvc,"GET",ROOT+"?userId=910001&sortBy=id&sortOrder=asc&page=1&size=1",token(ADMIN_A),null,200);
            check(next.path("content").size()==1&&next.path("content").get(0).path("orderId").asLong()==ORDER_B,
                    "The next page must contain the second owned purchase");
            JsonNode filtered=send(mvc,"GET",ROOT+"?userId=910001&status=PAID&deliveryStatus=READY&keyword=ALPHA&sortBy=id&sortOrder=asc",token(ADMIN_A),null,200);
            check(filtered.path("content").size()==1&&filtered.path("totalElements").asLong()==1,"Status, delivery, member and case-insensitive keyword filters must combine");
            JsonNode detail=send(mvc,"GET",ROOT+"/"+ORDER_A,token(ADMIN_A),null,200);
            check(detail.path("userId").asLong()==910001&&detail.path("items").size()==1&&detail.path("items").get(0).path("price").asLong()==2000,
                    "A current administrator must read another member's original purchase snapshot");
            Map<String,Fingerprint> before=nonDeliveryFingerprint();
            JsonNode changed=send(mvc,"PATCH",delivery(ORDER_A),token(ADMIN_A),Map.of("deliveryStatus","SHIPPING"),200);
            JsonNode loaded=send(mvc,"GET",ROOT+"/"+ORDER_A,token(ADMIN_B),null,200);
            check(changed.path("deliveryStatus").asText().equals("SHIPPING")&&loaded.equals(changed),"Delivery update must be committed and visible to another administrator");
            check(nonDeliveryFingerprint().equals(before),"Payment status, amounts, items, stock, events, Cart and User fields must remain unchanged");
            for(long user:List.of(USER,SHELTER,PENDING,MISSING)) {
                send(mvc,"GET",ROOT,token(user),null,403);
                send(mvc,"GET",ROOT+"/"+ORDER_A,token(user),null,403);
                send(mvc,"PATCH",delivery(ORDER_A),token(user),Map.of("deliveryStatus","DELIVERED"),403);
            }
            send(mvc,"GET",ROOT,tokenWithRole(ADMIN_A,"ROLE_USER"),null,403);
            for(String path:List.of(ROOT,ROOT+"/"+ORDER_A))send(mvc,"GET",path,null,null,401);
            send(mvc,"PATCH",delivery(ORDER_A),null,Map.of("deliveryStatus","DELIVERED"),401);
            send(mvc,"POST",ROOT,token(ADMIN_A),Map.of(),403);
            send(mvc,"DELETE",ROOT+"/"+ORDER_A,token(ADMIN_A),null,403);
            send(mvc,"GET",delivery(ORDER_A),token(ADMIN_A),null,403);
            check(nonDeliveryFingerprint().equals(before)&&deliveryValue(ORDER_A).equals("SHIPPING"),"Denied requests must not change any protected row or the committed delivery state");
        });
    }

    @Test
    void givenNullDeliveryOrMissingOrders_whenUpdatingOrReading_thenReturnSafe500AndOriginalMissingBoundariesWithFullRollbackAndNoPrivateLogs() {
        http(pool,(mvc,context)->{
            Map<String,Fingerprint> before=fingerprint();
            Map<String,Object> body=new LinkedHashMap<>();body.put("deliveryStatus",null);
            Logger logger=(Logger)LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            var events=new ListAppender<ILoggingEvent>();events.start();logger.addAppender(events);
            try {
                send(mvc,"PATCH",delivery(ORDER_A),token(ADMIN_A),body,500);
                for(ILoggingEvent event:events.list) {
                    check(!event.getLoggerName().equals("org.hibernate.engine.jdbc.spi.SqlExceptionHelper")
                                    &&event.getThrowableProxy()==null,
                            "Rejected null delivery must not emit Hibernate SQL failure or exception details");
                    String message=event.getFormattedMessage();
                    check(!message.contains("Failing row")&&!message.contains("SqlExceptionHelper"),
                            "Rejected null delivery must not log a database failed-row diagnostic");
                    for(String column:List.of("delivery_address","receiver_name","receiver_phone","delivery_message"))
                        check(!message.contains(ownedOrders.get(ORDER_A).get(column).toString()),
                                "Rejected null delivery must not log synthetic recipient, address or memo values");
                }
            } finally {logger.detachAppender(events);events.stop();}
            check(fingerprint().equals(before),"Null delivery must return a safe 500 and roll back the writer transaction without changing any fixture table");released(pool);
            send(mvc,"GET",ROOT+"/999999",token(ADMIN_A),null,404);
            send(mvc,"PATCH",delivery(999999),token(ADMIN_A),Map.of("deliveryStatus","DELIVERED"),400);
            check(fingerprint().equals(before),"Missing detail and update must preserve all fifteen tables");
        });
    }

    @Test
    void givenTwoDifferentCurrentAdminsAndAnOrderHolder_whenBothDeliveryUpdatesWait_thenSerializeOnTheOrderAndPreserveEveryOtherField() {
        http(pool,(mvc,context)->{
            Map<String,Fingerprint> before=nonDeliveryFingerprint();
            ExecutorService workers=Executors.newFixedThreadPool(2);unresolvedWorkers=true;
            try {
                Future<HttpResult> a,b;
                try(Connection control=probe.getConnection()) {
                    control.setAutoCommit(false);
                    try(var statement=control.prepareStatement("SELECT id FROM pawbridge_store.orders WHERE id=? FOR UPDATE")){
                        statement.setLong(1,ORDER_A);statement.setQueryTimeout(3);statement.execute();
                    }
                    int holder;
                    try(var statement=control.prepareStatement("SELECT pg_backend_pid()")){try(var rows=statement.executeQuery()){rows.next();holder=rows.getInt(1);}}
                    a=workers.submit(()->exchange(mvc,"PATCH",delivery(ORDER_A),token(ADMIN_A),Map.of("deliveryStatus","SHIPPING")));
                    b=workers.submit(()->exchange(mvc,"PATCH",delivery(ORDER_A),token(ADMIN_B),Map.of("deliveryStatus","DELIVERED")));
                    Set<Integer> waiting=waitForOrderLocks(control,holder);
                    check(waiting.size()==2,"Different administrators must occupy two physical PIDs blocked on the same actual Order SELECT lock");
                    control.commit();
                }
                HttpResult ar=a.get(6,TimeUnit.SECONDS),br=b.get(6,TimeUnit.SECONDS);
                check(ar.status()==200&&br.status()==200&&ar.body().path("deliveryStatus").asText().equals("SHIPPING")
                        &&br.body().path("deliveryStatus").asText().equals("DELIVERED"),"Both serialized updates must return their own committed delivery snapshot");
                check(Set.of("SHIPPING","DELIVERED").contains(deliveryValue(ORDER_A))&&nonDeliveryFingerprint().equals(before),
                        "Only delivery and its audit timestamp may change; no deterministic winner is invented");
            } finally {
                workers.shutdown();boolean settled=false;
                try {settled=workers.awaitTermination(8,TimeUnit.SECONDS);if(!settled){workers.shutdownNow();settled=workers.awaitTermination(3,TimeUnit.SECONDS);}}
                catch(InterruptedException interrupted){workers.shutdownNow();Thread.currentThread().interrupt();throw new AssertionError("Interrupted workers remain fenced from cleanup");}
                finally {unresolvedWorkers=!settled;}
                check(settled,"Workers must terminate before owned order restoration or pool close");
            }
            released(pool);
        });
    }
    private static String delivery(long id){return ROOT+"/"+id+"/delivery-status";}
    private String deliveryValue(long id){return jdbc.queryForObject("SELECT delivery_status FROM pawbridge_store.orders WHERE id=?",String.class,id);}
    private static Set<Integer> waitForOrderLocks(Connection control,int holder)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        do {
            try(var clear=control.prepareStatement("SELECT pg_stat_clear_snapshot()")){clear.execute();}
            Set<Integer> found=new LinkedHashSet<>();
            try(var statement=control.prepareStatement("SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND pid<>pg_backend_pid() AND state='active' AND wait_event_type='Lock' AND (?=ANY(pg_blocking_pids(pid)) OR EXISTS(SELECT 1 FROM unnest(pg_blocking_pids(pid)) b WHERE ?=ANY(pg_blocking_pids(b)))) AND query ILIKE '%pawbridge_store.orders%' AND (query ILIKE '%for update%' OR query ILIKE '%for no key update%')")) {
                statement.setInt(1,holder);statement.setInt(2,holder);statement.setQueryTimeout(2);try(var rows=statement.executeQuery()){while(rows.next())found.add(rows.getInt(1));}
            }
            if(found.size()==2)return found;Thread.sleep(20);
        }while(System.nanoTime()<until);
        throw new AssertionError("Both current administrators must reach the controlled Order lock");
    }

    private void http(HikariDataSource source,HttpCheck action) {
        observed("actual secured order MVC/JPA", () -> {
            AtomicReference<EntityManagerFactory> factory=new AtomicReference<>();
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
                    JacksonAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class,ValidationAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,WebMvcAutoConfiguration.class,SpringDataWebAutoConfiguration.class,SecurityAutoConfiguration.class,SecurityFilterAutoConfiguration.class))
                    .withUserConfiguration(StoreOrderAdministrationConfiguration.class,ReadSecurityConfiguration.class)
                    .withPropertyValues("pawbridge.store-order-administration.enabled=true","pawbridge.store-carts.enabled=false","pawbridge.store-direct-orders.enabled=false","spring.sql.init.mode=never","spring.jmx.enabled=false")
                    .withBean("writeDataSource",DataSource.class,() -> source,definition -> definition.setDestroyMethodName(""))
                    .withBean("writeJdbc",JdbcTemplate.class,() -> { var selected=new JdbcTemplate(source); selected.setQueryTimeout(5); return selected; })
                    .withBean(PostTokenAuthenticator.class,() -> new PostTokenAuthenticator(KEY))
                    .run(context -> {
                        check(context.getStartupFailure()==null,"The real Store order and security configuration must start");
                        factory.set(context.getBean("storeDirectOrderEntityManagerFactory",EntityManagerFactory.class));
                        MockMvc mvc=MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
                        try { action.run(mvc,context); }
                        catch(AssertionError failure) { throw failure; }
                        catch(Exception failure) { throw new AssertionError("Owned order HTTP observation failed: "+failure.getClass().getName()); }
                    });
            check(factory.get()!=null && !factory.get().isOpen(),"The owned context must close its Store EntityManagerFactory");
            check(!source.isClosed(),"Context close must retain the external owned pool until its owner closes it"); released(source);
        });
    }

    private HikariDataSource newPool(String name) {
        HikariConfig config=new HikariConfig(); config.setJdbcUrl(settings.jdbcUrl()); config.setUsername(settings.username()); config.setPassword(settings.password());
        config.setPoolName(name); config.setMaximumPoolSize(2); config.setMinimumIdle(0); config.setConnectionTimeout(2000);
        config.setValidationTimeout(1000); config.setInitializationFailTimeout(-1); config.setReadOnly(false);
        config.addDataSourceProperty("connectTimeout","3"); config.addDataSourceProperty("socketTimeout","10"); return new HikariDataSource(config);
    }
    private static String token(long user){return tokenWithRole(user,"ROLE_ADMIN");}
    private static String tokenWithRole(long user,String role){return "Bearer "+Jwts.builder().claim("userId",user).claim("role",role).expiration(new Date(System.currentTimeMillis()+300_000)).signWith(KEY).compact();}
    private static JsonNode send(MockMvc mvc,String method,String path,String authorization,Map<String,Object> body,int expected)throws Exception {
        HttpResult response=exchange(mvc,method,path,authorization,body);
        check(response.status()==expected,"Unexpected administrator HTTP status: expected="+expected+",actual="+response.status());return response.body();
    }
    private static HttpResult exchange(MockMvc mvc,String method,String path,String authorization,Map<String,Object> body)throws Exception {
        var request=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),path).servletPath(path.split("\\?",2)[0])
                .contentType(MediaType.APPLICATION_JSON).characterEncoding(StandardCharsets.UTF_8)
                .header("X-User-Id",Long.toString(ADMIN_A)).header("X-User-Role","ROLE_ADMIN");
        if(authorization!=null)request.header("Authorization",authorization);if(body!=null)request.content(JSON.writeValueAsString(body));
        var response=mvc.perform(request).andReturn().getResponse();check(response.getHeader("Set-Cookie")==null,"Administrator routes must remain stateless");
        String content=response.getContentAsString(StandardCharsets.UTF_8);
        return new HttpResult(response.getStatus(),content.isEmpty()?JSON.getNodeFactory().nullNode():JSON.readTree(content));
    }
    private Map<String,Fingerprint> fingerprint(){return fingerprint(false);}
    private Map<String,Fingerprint> nonDeliveryFingerprint(){return fingerprint(true);}
    private Map<String,Fingerprint> fingerprint(boolean ignoreOwnedDelivery){
        Map<String,Fingerprint> state=new LinkedHashMap<>();TABLE_KEYS.forEach((table,key)->{
            String value=ignoreOwnedDelivery&&table.equals("pawbridge_store.orders")
                    ?"CASE WHEN id=910010 THEN to_jsonb(t)-'delivery_status'-'updated_at' ELSE to_jsonb(t) END":"row_to_json(t)";
            Map<String,Object> row=jdbc.queryForMap("SELECT count(*) AS n,md5(COALESCE(string_agg(("+value+")::text,',' ORDER BY "+key+"),'')) AS digest FROM "+table+" t");
            state.put(table,new Fingerprint(((Number)row.get("n")).longValue(),(String)row.get("digest")));
        });return state;
    }
    private static void released(HikariDataSource source) {
        check(!TransactionSynchronizationManager.isActualTransactionActive() && !TransactionSynchronizationManager.hasResource(source),"No order transaction or User binding may escape an operation");
        check(source.getHikariPoolMXBean().getActiveConnections()==0 && source.getHikariPoolMXBean().getThreadsAwaitingConnection()==0,"Every owned two-slot pool must return its connection after success or rollback");
    }
    private static void verifyServer(JdbcTemplate jdbc,Settings settings) {
        jdbc.setQueryTimeout(5);
        Map<String,Object> identity=jdbc.queryForMap("SELECT current_database() AS db,current_user AS actor,session_user AS session,current_setting('server_version_num') AS version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) AS owner");
        check(settings.database().equals(identity.get("db")) && settings.username().equals(identity.get("actor"))
                && settings.username().equals(identity.get("session")) && "170011".equals(identity.get("version")) && !settings.username().equals(identity.get("owner")),"Owned PostgreSQL target and separate writer identity must match");
        List<String> markers=jdbc.query("SELECT marker FROM public.pawbridge_owned_fixture",(row,index) -> row.getString(1));
        check(markers.size()==1 && settings.marker().equals(markers.getFirst()),"Only the exact isolated fixture marker is accepted");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT pg_has_role(current_user,(SELECT datdba FROM pg_database WHERE datname=current_database()),'MEMBER') FROM pg_roles WHERE rolname=current_user",Boolean.class)),"The writer must have no administrative or owner inheritance");
        for(String schema:List.of("public","pawbridge_store","pawbridge_user"))
            check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_schema_privilege(current_user,?,'CREATE')",Boolean.class,schema)),"The writer must not create schema objects");
        for(String table:TABLE_KEYS.keySet())
            check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,'SELECT') AND NOT has_table_privilege(current_user,?,'TRUNCATE,REFERENCES,TRIGGER')",Boolean.class,table,table)),"Only selected nonadministrative table permissions may be used");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_column_privilege(current_user,'pawbridge_user.users','user_id','UPDATE') AND NOT has_table_privilege(current_user,'pawbridge_user.users','INSERT,DELETE') AND NOT has_table_privilege(current_user,'pawbridge_user.contact_deletions','INSERT,UPDATE,DELETE')",Boolean.class)),"User permissions must only permit current-state observation and the required row lock");
    }
    private static void observed(String phase,Checked action) {
        try { action.run(); } catch(AssertionError failure) { throw failure; }
        catch(Throwable failure) { throw new AssertionError("Owned direct order observation failed: "+phase+"; type="+failure.getClass().getName()); }
    }
    private static void check(boolean condition,String fixedMessage) { if(!condition) throw new AssertionError(fixedMessage); }
    private interface Checked { void run() throws Exception; }
    private interface HttpCheck { void run(MockMvc mvc,ApplicationContext context) throws Exception; }
    private record HttpResult(int status,JsonNode body) { }
    private record Fingerprint(long count,String digest) { }
    private record Settings(String jdbcUrl,String database,String username,String password,String marker) {
        static Settings load() throws Exception {
            String path=System.getenv("P13_PG_SETTINGS"); check(path!=null && !path.isBlank(),"P13_PG_SETTINGS is mandatory; owned tests must not skip");
            Path file=Path.of(path);
            check(file.isAbsolute() && file.normalize().equals(file) && Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file),"Settings must be an absolute regular non-symlink file");
            check(Files.getPosixFilePermissions(file,LinkOption.NOFOLLOW_LINKS).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))
                    && ((Number)Files.getAttribute(file,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()==1,"Settings must have exact0600 permissions and one hard link");
            check(System.getProperty("user.name").equals(Files.getOwner(file).getName()) && Files.getOwner(file).equals(Files.getOwner(file.getParent())),"Settings and immediate parent must belong to this process owner");
            for(Path parent=file.getParent();parent!=null;parent=parent.getParent()) check(!Files.isSymbolicLink(parent),"Settings ancestors cannot be symbolic links");
            Set<PosixFilePermission> permissions=Files.getPosixFilePermissions(file.getParent(),LinkOption.NOFOLLOW_LINKS);
            check(!permissions.contains(PosixFilePermission.GROUP_WRITE) && !permissions.contains(PosixFilePermission.OTHERS_WRITE),"Settings parent must reject other-user writes");
            Properties values=new Properties(); try(InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) { values.load(input); }
            String database=required(values,"database"),url=required(values,"jdbcUrl"),username=required(values,"username"),marker=required(values,"marker");
            check(database.matches("pb_admin_order_owned_[0-9a-f]{12}") && url.equals("jdbc:postgresql://127.0.0.1:52620/"+database),"Only the exact owned loopback database target is allowed");
            check(username.equals("pb_admin_order_writer") && marker.matches("[0-9a-f]{32}"),"Writer and marker must be explicit");
            return new Settings(url,database,username,required(values,"password"),marker);
        }
        private static String required(Properties values,String name) { String value=values.getProperty(name); check(value!=null && !value.isBlank(),"Every owned setting is required"); return value; }
        @Override public String toString() { return "OwnedAdminOrderSettings[redacted]"; }
    }
}
