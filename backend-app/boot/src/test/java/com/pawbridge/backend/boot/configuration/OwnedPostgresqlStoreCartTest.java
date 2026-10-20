package com.pawbridge.backend.boot.configuration;

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
import org.springframework.boot.autoconfigure.AutoConfigurations;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual secured Cart HTTP/JPA against a fresh, owned and minimally writable fixture. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class OwnedPostgresqlStoreCartTest {
    private static final long BUYER=910001, OTHER=910002, PENDING=910003, SKU=73002, FOREIGN=990001;
    private static final String ROOT="/api/v1/carts";
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final SecretKey KEY=Keys.hmacShaKeyFor("owned-cart-public-synthetic-signing-key-20261021-only".getBytes(StandardCharsets.UTF_8));
    private static final Map<String,String> TABLE_KEYS=Map.ofEntries(
            Map.entry("pawbridge_user.users","user_id"),Map.entry("pawbridge_user.contact_deletions","user_id"),
            Map.entry("pawbridge_store.categories","id"),Map.entry("pawbridge_store.option_groups","id"),
            Map.entry("pawbridge_store.option_values","id"),Map.entry("pawbridge_store.products","id"),
            Map.entry("pawbridge_store.product_skus","id"),Map.entry("pawbridge_store.sku_values","id"),
            Map.entry("pawbridge_store.wishlists","id"),Map.entry("pawbridge_store.orders","id"),
            Map.entry("pawbridge_store.order_items","id"),Map.entry("pawbridge_store.outbox","id"),
            Map.entry("pawbridge_store.product_search_documents","product_id"),
            Map.entry("pawbridge_store.carts","id"),Map.entry("pawbridge_store.cart_items","id"));
    private Settings settings;
    private HikariDataSource pool;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource probe;
    private Map<String,String> baseline;
    private boolean unresolvedWorkers;

    @BeforeAll
    void given__owned_cart_settings__when__opening_fixture__then__verify_target_marker_and_minimum_permissions() {
        observed("owned Cart identity",()->{
            settings=Settings.load();
            probe=new DriverManagerDataSource(settings.jdbcUrl(),settings.username(),settings.password());
            Properties timeouts=new Properties();timeouts.setProperty("connectTimeout","3");timeouts.setProperty("socketTimeout","5");probe.setConnectionProperties(timeouts);
            verifyServer(new JdbcTemplate(probe),settings);
            HikariConfig config=new HikariConfig();config.setJdbcUrl(settings.jdbcUrl());config.setUsername(settings.username());config.setPassword(settings.password());
            config.setPoolName("owned-cart-writer");config.setMaximumPoolSize(2);config.setMinimumIdle(0);config.setConnectionTimeout(2000);config.setValidationTimeout(1000);config.setInitializationFailTimeout(-1);
            config.addDataSourceProperty("connectTimeout","3");config.addDataSourceProperty("socketTimeout","10");pool=new HikariDataSource(config);jdbc=new JdbcTemplate(pool);jdbc.setQueryTimeout(5);
            check(count("SELECT count(*) FROM pawbridge_store.carts WHERE user_id IN (910001,910002,910003,910099)")==0,"Owned Cart users must start without any earlier trial rows");
            for(String table:List.of("pawbridge_store.orders","pawbridge_store.order_items","pawbridge_store.outbox","pawbridge_store.product_skus","pawbridge_store.products","pawbridge_store.product_search_documents"))
                check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,'INSERT,UPDATE,DELETE')",Boolean.class,table)),"Cart writer must not mutate stock, orders or event/search tables");
            baseline=fingerprint();released();
        });
    }

    @BeforeEach
    void given__foreign_baseline__when__starting_case__then__require_no_owned_rows_or_live_workers() {
        check(!unresolvedWorkers && fingerprint().equals(baseline),"Cases must preserve all fifteen fixture tables");
    }

    @AfterEach
    void given__pre_registered_cart_users__when__case_finishes__then__clean_only_their_carts_and_preserve_every_foreign_row() {
        check(!unresolvedWorkers,"Unfinished requests forbid row cleanup or pool shutdown");released();
        jdbc.update("DELETE FROM pawbridge_store.cart_items WHERE cart_id IN (SELECT id FROM pawbridge_store.carts WHERE user_id IN (?,?,?,?))",BUYER,OTHER,PENDING,910099L);
        jdbc.update("DELETE FROM pawbridge_store.carts WHERE user_id IN (?,?,?,?)",BUYER,OTHER,PENDING,910099L);
        check(fingerprint().equals(baseline),"Only pre-dispatch owned Cart rows may have changed; foreign tables and rows must be identical");released();
    }

    @AfterAll
    void given__settled_requests__when__fixture_owner_closes__then__close_only_owned_pool() {
        check(!unresolvedWorkers,"Unfinished request evidence must remain available");
        if(pool!=null){pool.close();check(pool.isClosed(),"Owned Cart pool must close");}
    }

    @Test
    void given__verified_buyer_and_forged_headers__when__managing_cart__then__preserve_original_wire_and_immediately_persist_only_own_rows() {
        http((mvc,context)->{
            JsonNode empty=send(mvc,"GET",ROOT,BUYER,null,200);check(empty.isArray()&&empty.isEmpty(),"New Cart GET must be an empty array");
            send(mvc,"POST",ROOT+"/items",BUYER,Map.of("skuId",SKU,"quantity",2,"userId",FOREIGN),200);
            JsonNode first=send(mvc,"GET",ROOT,BUYER,null,200);check(first.isArray()&&first.size()==1,"Cart GET must use the original array shape");
            JsonNode item=first.get(0);Set<String> names=new HashSet<>();item.fieldNames().forEachRemaining(names::add);
            check(names.equals(Set.of("id","skuId","skuCode","productName","price","quantity","totalPrice"))&&item.path("id").isNull()
                    &&item.path("skuId").asLong()==SKU&&item.path("quantity").asInt()==2&&item.path("price").asLong()==2000&&item.path("totalPrice").asLong()==4000,
                    "Original null item ID, price and quantity fields must survive DB-backed persistence");
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_store.carts c JOIN pawbridge_store.cart_items i ON i.cart_id=c.id WHERE c.user_id=? AND i.product_sku_id=? AND i.sku_id=? AND i.quantity=2 AND c.created_at IS NOT NULL AND i.created_at IS NOT NULL",Long.class,BUYER,SKU,SKU)==1,
                    "Both existing SKU columns and JPA audit fields must be committed before HTTP success");
            var memberReads=new com.pawbridge.backend.store.internal.memberread.infrastructure.JdbcStoreMemberReads(
                    new NamedParameterJdbcTemplate(jdbc),context.getBean("storeDirectOrderReads",TransactionTemplate.class));
            var persisted=memberReads.cart(BUYER).orElseThrow();
            check(persisted.userId()==BUYER&&persisted.items().size()==1&&persisted.items().getFirst().productSkuId()==SKU
                    &&persisted.items().getFirst().quantity()==2&&memberReads.cart(OTHER).isEmpty(),
                    "Existing User MyPage Cart reads must see committed SKU data while an uncreated Cart remains absent");
            send(mvc,"POST",ROOT+"/items",BUYER,Map.of("skuId",SKU,"quantity",3),200);
            check(send(mvc,"GET",ROOT,BUYER,null,200).get(0).path("quantity").asInt()==5,"Add must accumulate existing SKU quantity");
            send(mvc,"PATCH",ROOT+"/items/"+SKU+"?quantity=4",BUYER,null,200);
            check(send(mvc,"GET",ROOT,BUYER,null,200).get(0).path("quantity").asInt()==4,"PATCH query quantity must set rather than accumulate");
            check(send(mvc,"GET",ROOT,OTHER,null,200).isEmpty(),"Another authenticated member must not see the buyer Cart");
            check(memberReads.cart(OTHER).isEmpty(),"GET on a never-created Cart must not create an empty parent row");
            send(mvc,"PATCH",ROOT+"/items/"+SKU+"?quantity=0",BUYER,null,200);
            check(send(mvc,"GET",ROOT,BUYER,null,200).isEmpty(),"Nonpositive PATCH quantity must remove the item");
            send(mvc,"POST",ROOT+"/items",BUYER,Map.of("skuId",SKU,"quantity",1),200);
            send(mvc,"DELETE",ROOT+"/items/"+SKU,BUYER,null,200);check(send(mvc,"GET",ROOT,BUYER,null,200).isEmpty(),"DELETE item must persist removal");
            send(mvc,"POST",ROOT+"/items",BUYER,Map.of("skuId",SKU,"quantity",1),200);
            send(mvc,"DELETE",ROOT,BUYER,null,200);check(send(mvc,"GET",ROOT,BUYER,null,200).isEmpty(),"DELETE Cart must persist clearing");
            check(memberReads.cart(BUYER).isPresent()&&memberReads.cart(BUYER).orElseThrow().items().isEmpty(),
                    "Clearing items must preserve the original persisted-empty Cart versus never-created distinction");
            check(count("SELECT quantity FROM pawbridge_store.cart_items WHERE id=990041")==3,"Forged identity must never mutate the protected foreign Cart");
        });
    }

    @Test
    void given__invalid_or_unavailable_members_and_overflow__when__calling_cart__then__reject_without_partial_cart_or_cross_domain_writes() {
        http((mvc,context)->{
            send(mvc,"GET",ROOT,null,null,401);
            for(long user:List.of(PENDING,910099L)) {
                send(mvc,"GET",ROOT,user,null,403);
                send(mvc,"POST",ROOT+"/items",user,Map.of("skuId",SKU,"quantity",1),403);
            }
            for(Map<String,Object> body:List.<Map<String,Object>>of(Map.of("skuId",999999,"quantity",1),Map.of("skuId",SKU,"quantity",0),Map.of("skuId",73005,"quantity",2))) {
                send(mvc,"POST",ROOT+"/items",BUYER,body,400);
                check(fingerprint().equals(baseline),"Invalid SKU, quantity and price overflow must roll back parent and item creation");
            }
            send(mvc,"POST",ROOT+"/items",BUYER,Map.of("skuId",SKU,"quantity",1),200);
            send(mvc,"PATCH",ROOT+"/items/"+SKU+"?quantity="+Integer.MAX_VALUE,BUYER,null,200);
            Map<String,String> before=fingerprint();
            send(mvc,"POST",ROOT+"/items",BUYER,Map.of("skuId",SKU,"quantity",1),400);
            check(fingerprint().equals(before),"Quantity accumulation overflow must preserve the previous committed quantity");
            send(mvc,"GET",ROOT+"/reset",BUYER,null,403);
        });
    }

    @Test
    void given__two_real_sessions_waiting_on_one_member_lock__when__adding_same_sku__then__commit_one_cart_one_item_and_both_quantities() {
        http((mvc,context)->{
            ExecutorService workers=Executors.newFixedThreadPool(2);List<Future<Integer>> futures=new ArrayList<>();
            unresolvedWorkers=true;
            try(Connection control=probe.getConnection()) {
                control.setAutoCommit(false);
                try(var statement=control.prepareStatement("SELECT user_id FROM pawbridge_user.users WHERE user_id=? FOR UPDATE")) {
                    statement.setLong(1,BUYER);statement.setQueryTimeout(3);statement.execute();
                }
                int holder;
                try(var statement=control.prepareStatement("SELECT pg_backend_pid()")){try(var rows=statement.executeQuery()){rows.next();holder=rows.getInt(1);}}
                for(int quantity:List.of(2,3)) futures.add(workers.submit(()->exchange(mvc,"POST",ROOT+"/items",BUYER,Map.of("skuId",SKU,"quantity",quantity)).status()));
                Set<Integer> waiting=new HashSet<>();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(System.nanoTime()<deadline) {
                    try(var clear=control.prepareStatement("SELECT pg_stat_clear_snapshot()")){clear.execute();}
                    waiting.clear();
                    try(var statement=control.prepareStatement("SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND pid<>pg_backend_pid() AND state='active' AND wait_event_type='Lock' AND ( ? = ANY(pg_blocking_pids(pid)) OR EXISTS (SELECT 1 FROM unnest(pg_blocking_pids(pid)) b WHERE ? = ANY(pg_blocking_pids(b))))")) {
                        statement.setInt(1,holder);statement.setInt(2,holder);statement.setQueryTimeout(2);
                        try(var rows=statement.executeQuery()){while(rows.next())waiting.add(rows.getInt(1));}
                    }
                    if(waiting.size()==2)break;Thread.sleep(20);
                }
                check(waiting.size()==2,"Both independent application PIDs must reach the actual member lock/blocker chain");
                control.commit();
                for(Future<Integer> future:futures)check(future.get(5,TimeUnit.SECONDS)==200,"Both locked Cart mutations must succeed");
            } finally {
                for(Future<?> future:futures)if(!future.isDone())future.cancel(true);
                workers.shutdownNow();boolean settled=workers.awaitTermination(5,TimeUnit.SECONDS);unresolvedWorkers=!settled;
                check(settled,"Workers must terminate before any fixture cleanup or pool close");
            }
            check(count("SELECT count(*) FROM pawbridge_store.carts WHERE user_id=910001")==1
                    &&count("SELECT count(*) FROM pawbridge_store.cart_items i JOIN pawbridge_store.carts c ON c.id=i.cart_id WHERE c.user_id=910001")==1
                    &&count("SELECT i.quantity FROM pawbridge_store.cart_items i JOIN pawbridge_store.carts c ON c.id=i.cart_id WHERE c.user_id=910001")==5,
                    "Row locking must preserve both additions without duplicate parent/item or lost update");released();
        });
    }

    private void http(HttpCheck action) {
        observed("secured Cart HTTP/JPA",()->{
            AtomicReference<EntityManagerFactory> factory=new AtomicReference<>();
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
                    JacksonAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class,ValidationAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,WebMvcAutoConfiguration.class,SecurityAutoConfiguration.class,SecurityFilterAutoConfiguration.class))
                    .withUserConfiguration(StoreCartConfiguration.class,ReadSecurityConfiguration.class)
                    .withPropertyValues("pawbridge.store-carts.enabled=true","spring.sql.init.mode=never","spring.jmx.enabled=false")
                    .withBean("writeDataSource",DataSource.class,()->pool,definition->definition.setDestroyMethodName(""))
                    .withBean("writeJdbc",JdbcTemplate.class,()->new JdbcTemplate(pool))
                    .withBean(PostTokenAuthenticator.class,()->new PostTokenAuthenticator(KEY))
                    .run(context->{
                        check(context.getStartupFailure()==null,"Only the real Cart and security configurations must start");
                        factory.set(context.getBean("storeDirectOrderEntityManagerFactory",EntityManagerFactory.class));
                        MockMvc mvc=MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
                        try{action.run(mvc,context);}catch(AssertionError failure){throw failure;}catch(Exception failure){throw new AssertionError("Owned Cart HTTP failed: "+failure.getClass().getName());}
                    });
            check(factory.get()!=null&&!factory.get().isOpen(),"Context close must release its single Store EntityManagerFactory");
            check(!pool.isClosed(),"Context must preserve the externally owned pool");released();
        });
    }
    private static String token(long user){return "Bearer "+Jwts.builder().claim("userId",user).claim("role","ROLE_USER").expiration(new Date(System.currentTimeMillis()+300_000)).signWith(KEY).compact();}
    private static JsonNode send(MockMvc mvc,String method,String path,Long user,Map<String,Object> body,int status)throws Exception {
        Result result=exchange(mvc,method,path,user,body);check(result.status()==status,"Unexpected Cart HTTP status: expected="+status+",actual="+result.status());
        if(status==200&&!method.equals("GET"))check(result.emptyBody(),"Original Cart mutation success must have an empty body");return result.body();
    }
    private static Result exchange(MockMvc mvc,String method,String url,Long user,Map<String,Object> body)throws Exception {
        String servlet=url.split("\\?",2)[0];var request=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),url).servletPath(servlet)
                .contentType(MediaType.APPLICATION_JSON).header("X-User-Id",Long.toString(FOREIGN)).header("X-User-Role","ROLE_ADMIN");
        if(user!=null)request.header("Authorization",token(user));if(body!=null)request.content(JSON.writeValueAsString(body));
        var response=mvc.perform(request).andReturn().getResponse();check(response.getHeader("Set-Cookie")==null,"Cart API must remain stateless");
        String content=response.getContentAsString(StandardCharsets.UTF_8);return new Result(response.getStatus(),content.isEmpty()?JSON.getNodeFactory().nullNode():JSON.readTree(content),content.isEmpty());
    }
    private long count(String sql){return jdbc.queryForObject(sql,Long.class);}
    private Map<String,String> fingerprint(){Map<String,String> result=new LinkedHashMap<>();TABLE_KEYS.forEach((table,key)->result.put(table,jdbc.queryForObject("SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY "+key+"),'')) FROM "+table+" t",String.class)));return result;}
    private void released(){check(!TransactionSynchronizationManager.isActualTransactionActive()&&!TransactionSynchronizationManager.hasResource(pool),"No transaction or connection binding may escape a Cart operation");check(pool.getHikariPoolMXBean().getActiveConnections()==0&&pool.getHikariPoolMXBean().getThreadsAwaitingConnection()==0,"All owned Cart connections must return after commit or rollback");}
    private static void observed(String phase,Checked action){try{action.run();}catch(AssertionError failure){throw failure;}catch(Throwable failure){throw new AssertionError("Owned Cart observation failed: "+phase+";type="+failure.getClass().getName());}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private interface Checked{void run()throws Exception;}
    private interface HttpCheck{void run(MockMvc mvc,ApplicationContext context)throws Exception;}
    private record Result(int status,JsonNode body,boolean emptyBody){}

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
    private record Settings(String jdbcUrl,String database,String username,String password,String marker) {
        static Settings load() throws Exception {
            String path=System.getenv("P11_PG_SETTINGS"); check(path!=null && !path.isBlank(),"P11_PG_SETTINGS is mandatory; owned tests must not skip");
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
            check(database.matches("pb_cart_owned_[0-9a-f]{12}") && url.equals("jdbc:postgresql://127.0.0.1:52620/"+database),"Only the exact owned loopback database target is allowed");
            check(username.equals("pb_cart_writer") && marker.matches("[0-9a-f]{32}"),"Writer and marker must be explicit");
            return new Settings(url,database,username,required(values,"password"),marker);
        }
        private static String required(Properties values,String name) { String value=values.getProperty(name); check(value!=null && !value.isBlank(),"Every owned setting is required"); return value; }
        @Override public String toString() { return "OwnedCartSettings[redacted]"; }
    }
}
