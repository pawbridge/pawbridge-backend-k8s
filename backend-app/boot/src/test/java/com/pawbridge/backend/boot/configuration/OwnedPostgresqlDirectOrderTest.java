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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real HTTP/security/JPA against an explicitly owned reduced Store fixture, never an existing database. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class OwnedPostgresqlDirectOrderTest {
    private static final String CREATE = "/api/v1/orders/direct", GET = "/api/v1/orders/";
    private static final long BUYER = 910001, OTHER = 910002, PENDING = 910003, FOREIGN_ORDER = 990010;
    private static final long NORMAL = 73002, CONCURRENT = 73008, BARRIER = 9273008;
    private static final Map<Long, Long> SKU_PRODUCTS = Map.of(73001L,71001L,73002L,71001L,73003L,71002L,
            73004L,71003L,73005L,71004L,73006L,71005L,73007L,71006L,73008L,71007L);
    private static final Map<String, String> TABLE_KEYS = Map.ofEntries(
            Map.entry("pawbridge_user.users","user_id"), Map.entry("pawbridge_user.contact_deletions","user_id"),
            Map.entry("pawbridge_store.categories","id"), Map.entry("pawbridge_store.option_groups","id"),
            Map.entry("pawbridge_store.option_values","id"), Map.entry("pawbridge_store.products","id"),
            Map.entry("pawbridge_store.product_skus","id"), Map.entry("pawbridge_store.sku_values","id"),
            Map.entry("pawbridge_store.wishlists","id"), Map.entry("pawbridge_store.orders","id"),
            Map.entry("pawbridge_store.order_items","id"), Map.entry("pawbridge_store.outbox","id"),
            Map.entry("pawbridge_store.product_search_documents","product_id"));
    private static final SecretKey KEY = Keys.hmacShaKeyFor(
            "owned-p9-public-synthetic-signing-key-20261020-only".getBytes(StandardCharsets.UTF_8));
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final List<Ownership> requests = new ArrayList<>();
    private final Set<Long> responseIds = new LinkedHashSet<>();
    private final Map<Long, Map<String,Object>> originalSkus = new LinkedHashMap<>(), originalProducts = new LinkedHashMap<>(), originalDocuments = new LinkedHashMap<>();
    private Settings settings;
    private HikariDataSource pool;
    private DriverManagerDataSource probe;
    private JdbcTemplate jdbc;
    private Map<String,Fingerprint> baseline;

    @BeforeAll
    void verifyOwnedFixtureBeforeOpeningTheApplicationPool() {
        observed("owned writer identity", () -> {
            settings = Settings.load();
            probe = new DriverManagerDataSource(settings.jdbcUrl(),settings.username(),settings.password());
            Properties timeouts = new Properties(); timeouts.setProperty("connectTimeout","3"); timeouts.setProperty("socketTimeout","5");
            probe.setConnectionProperties(timeouts); verifyServer(new JdbcTemplate(probe),settings);
            pool = newPool("owned-direct-order-writer"); jdbc = new JdbcTemplate(pool); jdbc.setQueryTimeout(5);
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_store.orders WHERE user_id IN (910001,910002,910003,910099)",Long.class)==0,
                    "Previous trial orders must not enter a new baseline");
            for (long sku : SKU_PRODUCTS.keySet()) {
                originalSkus.put(sku,jdbc.queryForMap("SELECT * FROM pawbridge_store.product_skus WHERE id=?",sku));
                check(count("SELECT count(*) FROM pawbridge_store.outbox WHERE aggregate_type='product-sku' AND aggregate_id=?",Long.toString(sku))==0,
                        "Previous trial SKU events must not enter a new baseline");
            }
            for (long product : new HashSet<>(SKU_PRODUCTS.values())) {
                originalProducts.put(product,jdbc.queryForMap("SELECT * FROM pawbridge_store.products WHERE id=?",product));
                originalDocuments.put(product,jdbc.queryForMap("SELECT *,tokens::text AS token_text FROM pawbridge_store.product_search_documents WHERE product_id=?",product));
            }
            baseline = fingerprint(); released(pool);
        });
    }

    @BeforeEach
    void requireThePreservedForeignBaseline() {
        observed("case baseline", () -> check(requests.isEmpty() && responseIds.isEmpty() && fingerprint().equals(baseline),
                "Each case must start from the exact thirteen-table baseline"));
    }

    @AfterEach
    void cleanOnlyPreDispatchOwnedOrdersAndTouchedFixtureProducts() {
        observed("owned cleanup", () -> {
            released(pool); Set<Long> ids = new LinkedHashSet<>(responseIds), products = new LinkedHashSet<>(); boolean unique = true;
            for (Ownership ownership : requests) {
                List<Long> found = orderIds(ownership); unique &= found.size()<=1; ids.addAll(found);
                if (SKU_PRODUCTS.containsKey(ownership.sku())) products.add(SKU_PRODUCTS.get(ownership.sku()));
            }
            for (long id : ids) check(id>0 && id!=FOREIGN_ORDER,"Cleanup cannot select a foreign order");
            for (long id : ids) {
                jdbc.update("DELETE FROM pawbridge_store.order_items WHERE order_id=?",id);
                jdbc.update("DELETE FROM pawbridge_store.orders WHERE id=?",id);
            }
            for (Map.Entry<Long,Long> entry : SKU_PRODUCTS.entrySet()) if (products.contains(entry.getValue())) {
                long sku = entry.getKey(); Map<String,Object> value = originalSkus.get(sku);
                jdbc.update("UPDATE pawbridge_store.product_skus SET price=?,stock_quantity=?,created_at=?,updated_at=? WHERE id=?",
                        value.get("price"),value.get("stock_quantity"),value.get("created_at"),value.get("updated_at"),sku);
            }
            for (long product : products) {
                Map<String,Object> value=originalProducts.get(product), document=originalDocuments.get(product);
                jdbc.update("UPDATE pawbridge_store.products SET name=?,status=?,created_at=?,updated_at=? WHERE id=?",
                        value.get("name"),value.get("status"),value.get("created_at"),value.get("updated_at"),product);
                jdbc.update("INSERT INTO pawbridge_store.product_search_documents(product_id,sku_id,source_name,source_option,analyzer_version,tokens) "
                        +"VALUES (?,?,?,?,?,?::tsvector) ON CONFLICT(product_id) DO UPDATE SET sku_id=excluded.sku_id,source_name=excluded.source_name,"
                        +"source_option=excluded.source_option,analyzer_version=excluded.analyzer_version,tokens=excluded.tokens",
                        product,document.get("sku_id"),document.get("source_name"),document.get("source_option"),document.get("analyzer_version"),document.get("token_text"));
            }
            requests.clear(); responseIds.clear();
            // Nontransactional sequence consumption is deliberately outside row fingerprints.
            check(fingerprint().equals(baseline),"Every owned row must be restored and every foreign row must remain unchanged");
            check(unique,"Each pre-dispatch owner/message key must identify at most one order"); released(pool);
        });
    }

    @AfterAll
    void closeOnlyTheOwnedPool() { if (pool!=null) { pool.close(); check(pool.isClosed(),"The owned application pool must close"); } }

    @Test
    void givenVerifiedBuyerAndForgedHeaders_whenBuyingThenReading_thenKeepFullPendingSchemaAndPurchaseSnapshotsWithoutOutbox() {
        http(pool,(mvc,context) -> {
            OwnedBody request=body(BUYER,NORMAL,2); request.value().put("userId",990001L);
            JsonNode created=send(mvc,"POST",CREATE,token(BUYER),request.value(),201); long id=remember(created,request.owner());
            check(fields(created).equals(Set.of("orderId","orderUuid","userId","totalAmount","status","deliveryStatus","receiverName","receiverPhone","deliveryAddress","deliveryMessage","createdAt","items")),"The complete original order response schema must survive");
            check(created.path("userId").asLong()==BUYER && created.path("totalAmount").asLong()==4000
                    && "PENDING".equals(created.path("status").asText()) && "READY".equals(created.path("deliveryStatus").asText()),"Only the verified JWT owner and original PENDING/READY defaults may be stored");
            UUID.fromString(created.path("orderUuid").asText());
            JsonNode item=created.path("items").get(0);
            check(fields(item).equals(Set.of("productName","skuCode","price","quantity")) && item.path("price").asLong()==2000
                    && item.path("quantity").asInt()==2 && "OWNED-P9-ORDERED".equals(item.path("skuCode").asText()),"The original purchase item snapshot must be complete");
            Map<String,Object> stored=jdbc.queryForMap("SELECT * FROM pawbridge_store.orders WHERE id=?",id);
            check(stored.get("created_at")!=null && stored.get("updated_at")!=null && !created.path("createdAt").isNull(),"Actual JPA auditing must populate the persisted order");
            check(stock(NORMAL)==6 && stock(73001)==5,"Only the requested SKU quantity must decrease");
            check(events(73001,73002).isEmpty(),"The SKU_UPDATED outbox was removed; buying must not write Store outbox rows");
            Map<String,Object> document=jdbc.queryForMap("SELECT * FROM pawbridge_store.product_search_documents WHERE product_id=71001");
            check(((Number)document.get("sku_id")).longValue()==73001 && KoreanSearchTerms.VERSION.equals(document.get("analyzer_version")),"Search must select the same lowest-price representative SKU");
            jdbc.update("UPDATE pawbridge_store.product_skus SET price=3333 WHERE id=?",NORMAL);
            jdbc.update("UPDATE pawbridge_store.products SET name='changed live product' WHERE id=71001");
            JsonNode own=send(mvc,"GET",GET+id,token(BUYER),null,200);
            check(sameOrder(created,own,id),"Own detail must preserve purchase snapshots after live price/name changes");
            Map<String,Fingerprint> before=fingerprint(); send(mvc,"GET",GET+id,token(OTHER),null,400);
            check(fingerprint().equals(before),"Another current member must not read or mutate this order"); released(pool);
        });
    }

    @Test
    void givenMissingPendingOrUnauthenticatedMembers_whenCallingExactOrderRoutes_thenIgnoreForgedIdentityAndWriteNothing() {
        http(pool,(mvc,context) -> {
            Map<String,Fingerprint> before=fingerprint();
            for (long user:List.of(PENDING,910099L)) {
                OwnedBody request=body(user,NORMAL,1); send(mvc,"POST",CREATE,token(user),request.value(),403);
                send(mvc,"GET",GET+FOREIGN_ORDER,token(user),null,403);
            }
            send(mvc,"POST",CREATE,null,Map.of(),401); send(mvc,"GET",GET+FOREIGN_ORDER,"Bearer invalid.synthetic.token",null,401);
            send(mvc,"POST","/api/v1/orders/extra",token(BUYER),Map.of(),403);
            check(fingerprint().equals(before),"DB current member state, JWT authentication and closed neighbors must prevent all writes"); released(pool);
        });
    }

    @ParameterizedTest @ValueSource(longs={73003,73004})
    void givenInsufficientStockOrInactiveProduct_whenBuying_thenPreserveOriginal500AndRollBackAllTables(long sku) {
        http(pool,(mvc,context) -> {
            Map<String,Fingerprint> before=fingerprint(); OwnedBody request=body(BUYER,sku,1);
            JsonNode error=send(mvc,"POST",CREATE,token(BUYER),request.value(),500);
            check("C004".equals(error.path("code").asText()) && fingerprint().equals(before),"Original stock/state IllegalState failures must remain atomic server errors");
        });
    }

    @Test
    void givenNonpositiveInputOrLateAmountOverflow_whenBuying_thenReturn400WithoutStockSearchEventOrOrderChanges() {
        http(pool,(mvc,context) -> {
            for (long[] input:List.of(new long[]{NORMAL,0},new long[]{0,1},new long[]{73005,2})) {
                Map<String,Fingerprint> before=fingerprint(); OwnedBody request=body(BUYER,input[0],(int)input[1]);
                JsonNode error=send(mvc,"POST",CREATE,token(BUYER),request.value(),400);
                check("C001".equals(error.path("code").asText()) && fingerprint().equals(before),"Validation and late multiplyExact overflow must roll back every preceding write");
            }
        });
    }

    @ParameterizedTest @ValueSource(strings={"search","constraint"})
    void givenSearchOrOriginalNotNullFailure_whenBuyingThenRetrying_thenRollBackAndRecoverNormally(String failure) {
        http(pool,(mvc,context) -> {
            long sku=failure.equals("search")?73006:NORMAL;
            OwnedBody request=body(BUYER,sku,1); if(failure.equals("constraint")) request.value().put("receiverName",null);
            Map<String,Fingerprint> before=fingerprint(); JsonNode error=send(mvc,"POST",CREATE,token(BUYER),request.value(),500);
            check("C004".equals(error.path("code").asText()) && fingerprint().equals(before),"Search and real NOT NULL errors must remain atomic 500");
            if(failure.equals("search")) jdbc.update("UPDATE pawbridge_store.products SET name='owned recovered search' WHERE id=71005");
            OwnedBody retry=body(BUYER,sku,1); JsonNode created=send(mvc,"POST",CREATE,token(BUYER),retry.value(),201);
            long id=remember(created,retry.owner()); check(sameOrder(created,send(mvc,"GET",GET+id,token(BUYER),null,200),id),"A valid retry must recover through the real service");
            check(stock(sku)==((Number)originalSkus.get(sku).get("stock_quantity")).intValue()-1,"Only the recovered request may consume stock");
        });
    }

    @Test
    void givenOriginalShippingRollbackCharsetConstraint_whenBuyingFourByteText_thenReturn400C007AndRollBackEveryWrite() {
        http(pool,(mvc,context) -> {
            OwnedBody request=body(BUYER,NORMAL,1); request.value().put("deliveryAddress","synthetic \uD83D\uDC36 address");
            Map<String,Fingerprint> before=fingerprint(); JsonNode error=send(mvc,"POST",CREATE,token(BUYER),request.value(),400);
            check("C007".equals(error.path("code").asText()) && fingerprint().equals(before),"Only the original Store V3 charset constraint must preserve 400/C007 and atomic rollback");
        });
    }

    @Test
    void givenActualWriteAndReadTemplates_whenResolvingTheCurrentBuyer_thenUseOnePhysicalConnectionCorrectReadModeAndOnlyWriteRowLocks() {
        http(pool,(mvc,context) -> {
            List<Integer> pids=new ArrayList<>();
            for (boolean readOnly:List.of(false,true)) {
                var template=context.getBean(readOnly?"storeDirectOrderReads":"storeDirectOrderWrites",TransactionTemplate.class);
                template.executeWithoutResult(status -> {
                    check((readOnly?"on":"off").equals(jdbc.queryForObject("SHOW transaction_read_only",String.class))
                            && "read committed".equals(jdbc.queryForObject("SHOW transaction_isolation",String.class)),"The actual Store templates must use the expected PostgreSQL mode and READ COMMITTED");
                    check(TransactionSynchronizationManager.hasResource(pool) && pool.getHikariPoolMXBean().getActiveConnections()==1,"JpaTM and User JDBC must share the sole pooled connection");
                    var actor=context.getBean(StoreOrderActors.class);
                    check((readOnly?actor.currentForRead(BUYER):actor.currentForWrite(BUYER)).active(),"Actual current User lookup must join this transaction");
                    pids.add(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                    try(Connection other=probe.getConnection();var statement=other.prepareStatement("SELECT user_id FROM pawbridge_user.users WHERE user_id=? FOR UPDATE NOWAIT")) {
                        statement.setLong(1,BUYER); statement.setQueryTimeout(3);
                        try(var rows=statement.executeQuery()) { check(readOnly && rows.next(),"Read must not take a User row lock; write must exclude another connection"); }
                    } catch(SQLException failure) { check(!readOnly && "55P03".equals(failure.getSQLState()),"Only write mode may produce the actual NOWAIT lock refusal"); }
                }); released(pool);
            }
            check(pids.getFirst().equals(pids.getLast()) && pool.getMaximumPoolSize()==1,"RW and RO must reuse the one-slot physical writer pool");
        });
    }

    @Test
    void givenTwoPhysicalWriterConnectionsAndDifferentBuyers_whenOneProductIsContended_thenCommitOnlyOnePurchaseWithoutOverselling() {
        http(pool,(first,firstContext) -> {
            try(HikariDataSource secondPool=newPool("owned-direct-order-second")) {
                http(secondPool,(second,secondContext) -> {
                    OwnedBody one=body(BUYER,CONCURRENT,1), two=body(OTHER,CONCURRENT,1);
                    ExecutorService executor=Executors.newFixedThreadPool(2); List<Future<HttpResult>> futures=new ArrayList<>();
                    try(Connection control=probe.getConnection()) {
                        advisory(control,true); CountDownLatch secondStarted=new CountDownLatch(1);
                        try {
                            futures.add(executor.submit(() -> exchange(first,"POST",CREATE,token(BUYER),one.value())));
                            waitForLockWaiters(control,1);
                            futures.add(executor.submit(() -> { secondStarted.countDown(); return exchange(second,"POST",CREATE,token(OTHER),two.value()); }));
                            check(secondStarted.await(2,TimeUnit.SECONDS),"The second independent request must start");
                            Set<Integer> waiting=waitForLockWaiters(control,2);
                            check(waiting.size()==2 && pool.getHikariPoolMXBean().getActiveConnections()==1
                                    && secondPool.getHikariPoolMXBean().getActiveConnections()==1,"Two distinct application PIDs must actually overlap while retaining one connection per pool");
                        } finally { advisory(control,false); }
                        List<Integer> statuses=new ArrayList<>();
                        for(int i=0;i<futures.size();i++) {
                            HttpResult result=futures.get(i).get(10,TimeUnit.SECONDS); statuses.add(result.status());
                            if(result.status()==201) remember(result.body(),i==0?one.owner():two.owner());
                        }
                        statuses.sort(Integer::compareTo);
                        check(statuses.equals(List.of(201,500)) && stock(CONCURRENT)==0
                                && count("SELECT count(*) FROM pawbridge_store.order_items WHERE product_sku_id=?",CONCURRENT)==1
                                && events(CONCURRENT).isEmpty(),"The contended source locks must allow one order, no outbox event and no negative stock");
                    } finally {
                        for(Future<?> future:futures) if(!future.isDone()) future.cancel(true);
                        executor.shutdownNow(); check(executor.awaitTermination(5,TimeUnit.SECONDS),"Every owned request thread must terminate before cleanup");
                    }
                    released(pool); released(secondPool);
                });
            }
        });
    }

    private void http(HikariDataSource source,HttpCheck action) {
        observed("actual secured order MVC/JPA", () -> {
            AtomicReference<EntityManagerFactory> factory=new AtomicReference<>();
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
                    JacksonAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class,ValidationAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,WebMvcAutoConfiguration.class,SecurityAutoConfiguration.class,SecurityFilterAutoConfiguration.class))
                    .withUserConfiguration(StoreDirectOrderConfiguration.class,ReadSecurityConfiguration.class)
                    .withPropertyValues("pawbridge.store-direct-orders.enabled=true","spring.sql.init.mode=never","spring.jmx.enabled=false")
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
        config.setPoolName(name); config.setMaximumPoolSize(1); config.setMinimumIdle(0); config.setConnectionTimeout(2000);
        config.setValidationTimeout(1000); config.setInitializationFailTimeout(-1); config.setReadOnly(false);
        config.addDataSourceProperty("connectTimeout","3"); config.addDataSourceProperty("socketTimeout","10"); return new HikariDataSource(config);
    }
    private OwnedBody body(long user,long sku,int quantity) {
        Ownership owner=new Ownership(user,sku,"OWNED-P9-"+UUID.randomUUID()); requests.add(owner);
        Map<String,Object> value=new LinkedHashMap<>(); value.put("skuId",sku); value.put("quantity",quantity);
        value.put("receiverName","synthetic receiver"); value.put("receiverPhone","0000000000");
        value.put("deliveryAddress","synthetic address"); value.put("deliveryMessage",owner.key()); return new OwnedBody(owner,value);
    }
    private long remember(JsonNode result,Ownership owner) {
        long id=result.path("orderId").asLong(); List<Long> observed=orderIds(owner);
        check(id>0 && id!=FOREIGN_ORDER && observed.size()==1 && observed.getFirst()==id
                && result.path("userId").asLong()==owner.user() && owner.key().equals(result.path("deliveryMessage").asText()),
                "Response identity must match the pre-dispatch User and exact request ownership key"); responseIds.add(id); return id;
    }
    private List<Long> orderIds(Ownership owner) {
        return jdbc.query("SELECT id FROM pawbridge_store.orders WHERE user_id=? AND delivery_message=?",(row,index) -> row.getLong(1),owner.user(),owner.key());
    }
    private static String token(long user) { return "Bearer "+Jwts.builder().claim("userId",user).claim("role","ROLE_USER")
            .expiration(new Date(System.currentTimeMillis()+300_000)).signWith(KEY).compact(); }
    private static JsonNode send(MockMvc mvc,String method,String path,String authorization,Map<String,Object> body,int expected) throws Exception {
        HttpResult result=exchange(mvc,method,path,authorization,body);
        check(result.status()==expected,"Unexpected Store HTTP status: expected="+expected+", actual="+result.status()); return result.body();
    }
    private static HttpResult exchange(MockMvc mvc,String method,String path,String authorization,Map<String,Object> body) throws Exception {
        var request=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),path).servletPath(path).contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8).header("X-User-Id","990001").header("X-User-Role","ROLE_ADMIN");
        if(authorization!=null) request.header("Authorization",authorization); if(body!=null) request.content(JSON.writeValueAsString(body));
        var result=mvc.perform(request).andReturn().getResponse(); check(result.getHeader("Set-Cookie")==null,"The order routes must remain stateless");
        String content=result.getContentAsString(StandardCharsets.UTF_8);
        return new HttpResult(result.getStatus(),content.isEmpty()?JSON.getNodeFactory().nullNode():JSON.readTree(content));
    }
    private static Set<String> fields(JsonNode value) { Set<String> names=new HashSet<>(); value.fieldNames().forEachRemaining(names::add); return names; }
    private boolean sameOrder(JsonNode created,JsonNode loaded,long id) {
        // Match the actual Root Hibernate JDBC UTC binding when reading TIMESTAMP(6).
        // Create maps before commit, but GET must equal the stored audit value exactly.
        LocalDateTime stored=jdbc.queryForObject("SELECT created_at FROM pawbridge_store.orders WHERE id=?",
                (row,index) -> row.getTimestamp(1,Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toLocalDateTime(),id);
        Duration createDifference=Duration.between(LocalDateTime.parse(created.path("createdAt").asText()),stored).abs();
        LocalDateTime loadedAudit=LocalDateTime.parse(loaded.path("createdAt").asText());
        ObjectNode first=created.deepCopy(), second=loaded.deepCopy(); first.remove("createdAt"); second.remove("createdAt");
        return stored.getNano()%1000==0 && createDifference.compareTo(Duration.ofNanos(1000))<0
                && loadedAudit.equals(stored) && first.equals(second);
    }
    private int stock(long sku) { return jdbc.queryForObject("SELECT stock_quantity FROM pawbridge_store.product_skus WHERE id=?",Integer.class,sku); }
    private long count(String sql,Object value) { return jdbc.queryForObject(sql,Long.class,value); }
    private List<Map<String,Object>> events(long... skus) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(long sku:skus) result.addAll(jdbc.queryForList("SELECT * FROM pawbridge_store.outbox WHERE aggregate_type='product-sku' AND aggregate_id=? ORDER BY id",Long.toString(sku)));
        return result;
    }
    private Map<String,Fingerprint> fingerprint() {
        Map<String,Fingerprint> state=new LinkedHashMap<>(); TABLE_KEYS.forEach((table,key) -> {
            Map<String,Object> value=jdbc.queryForMap("SELECT count(*) AS n,md5(COALESCE(string_agg(row_to_json(owned_row)::text,',' ORDER BY "+key+"),'')) AS digest FROM "+table+" owned_row");
            state.put(table,new Fingerprint(((Number)value.get("n")).longValue(),(String)value.get("digest")));
        }); return state;
    }
    private static void released(HikariDataSource source) {
        check(!TransactionSynchronizationManager.isActualTransactionActive() && !TransactionSynchronizationManager.hasResource(source),"No order transaction or User binding may escape an operation");
        check(source.getHikariPoolMXBean().getActiveConnections()==0 && source.getHikariPoolMXBean().getThreadsAwaitingConnection()==0,"Every owned one-slot pool must return its connection after success or rollback");
    }
    private static void advisory(Connection control,boolean acquire) throws SQLException {
        try(var statement=control.prepareStatement(acquire?"SELECT pg_advisory_lock(?)":"SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1,BARRIER); statement.setQueryTimeout(3); statement.execute();
        }
    }
    private static Set<Integer> waitForLockWaiters(Connection control,int minimum) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        do {
            Set<Integer> pids=new LinkedHashSet<>();
            try(var statement=control.prepareStatement("SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND state='active' AND wait_event_type='Lock'")) {
                statement.setQueryTimeout(2); try(var rows=statement.executeQuery()) { while(rows.next()) pids.add(rows.getInt(1)); }
            }
            if(pids.size()>=minimum) return pids;
            Thread.sleep(25);
        } while(System.nanoTime()<deadline);
        throw new AssertionError("Owned independent requests must reach their actual bounded database lock wait");
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
    private record Ownership(long user,long sku,String key) { }
    private record OwnedBody(Ownership owner,Map<String,Object> value) { }
    private record HttpResult(int status,JsonNode body) { }
    private record Fingerprint(long count,String digest) { }
    private record Settings(String jdbcUrl,String database,String username,String password,String marker) {
        static Settings load() throws Exception {
            String path=System.getenv("P9_PG_SETTINGS"); check(path!=null && !path.isBlank(),"P9_PG_SETTINGS is mandatory; owned tests must not skip");
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
            check(database.matches("pb_direct_order_owned_[0-9a-f]{12}") && url.equals("jdbc:postgresql://127.0.0.1:52620/"+database),"Only the exact owned loopback database target is allowed");
            check(username.equals("pb_direct_order_writer") && marker.matches("[0-9a-f]{32}"),"Writer and marker must be explicit");
            return new Settings(url,database,username,required(values,"password"),marker);
        }
        private static String required(Properties values,String name) { String value=values.getProperty(name); check(value!=null && !value.isBlank(),"Every owned setting is required"); return value; }
        @Override public String toString() { return "OwnedDirectOrderSettings[redacted]"; }
    }
}
