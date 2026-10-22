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

/** Secured Cart-to-checkout HTTP/JPA against a fresh owned P12 fixture; no external payment or source JPA oracle claim. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class OwnedPostgresqlStoreCartOrderTest {
    private static final String CREATE = "/api/v1/orders", GET = "/api/v1/orders/", CART = "/api/v1/carts";
    private static final long BUYER = 910001, OTHER = 910002, PENDING = 910003, FOREIGN_ORDER = 990010;
    private static final long NORMAL = 73002;
    private static final Map<Long, Long> SKU_PRODUCTS = Map.of(73001L,71001L,73002L,71001L,73003L,71002L,
            73004L,71003L,73005L,71004L,73006L,71005L,73007L,71006L,73008L,71007L);
    private static final Map<String, String> TABLE_KEYS = Map.ofEntries(
            Map.entry("pawbridge_user.users","user_id"), Map.entry("pawbridge_user.contact_deletions","user_id"),
            Map.entry("pawbridge_store.categories","id"), Map.entry("pawbridge_store.option_groups","id"),
            Map.entry("pawbridge_store.option_values","id"), Map.entry("pawbridge_store.products","id"),
            Map.entry("pawbridge_store.product_skus","id"), Map.entry("pawbridge_store.sku_values","id"),
            Map.entry("pawbridge_store.wishlists","id"), Map.entry("pawbridge_store.orders","id"),
            Map.entry("pawbridge_store.order_items","id"), Map.entry("pawbridge_store.outbox","id"),
            Map.entry("pawbridge_store.product_search_documents","product_id"),
            Map.entry("pawbridge_store.carts","id"),Map.entry("pawbridge_store.cart_items","id"));
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
    private boolean unresolvedWorkers;
    private final Set<Long> cartUsers=new LinkedHashSet<>();

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
            check(count("SELECT count(*) FROM pawbridge_store.carts WHERE user_id IN (910001,910002,910003,910099)")==0,
                    "Previous owned Cart rows must not enter the baseline");
            baseline = fingerprint(); released(pool);
        });
    }

    @BeforeEach
    void requireThePreservedForeignBaseline() {
        observed("case baseline", () -> {
            check(!unresolvedWorkers && cartUsers.isEmpty() && requests.isEmpty() && responseIds.isEmpty() && fingerprint().equals(baseline),
                    "Each case must start from the exact fifteen-table baseline");
            cartUsers.addAll(List.of(BUYER,OTHER,PENDING,910099L));
        });
    }

    @AfterEach
    void cleanOnlyPreDispatchOwnedOrdersAndTouchedFixtureProducts() {
        observed("owned cleanup", () -> {
            check(!unresolvedWorkers,"Unsettled requests forbid row cleanup");
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
            for(long user:cartUsers) {
                check(user==BUYER||user==OTHER||user==PENDING||user==910099L,"Cart cleanup must use pre-dispatch owned users only");
                jdbc.update("DELETE FROM pawbridge_store.cart_items WHERE cart_id IN (SELECT id FROM pawbridge_store.carts WHERE user_id=?)",user);
                jdbc.update("DELETE FROM pawbridge_store.carts WHERE user_id=?",user);
            }
            cartUsers.clear();requests.clear(); responseIds.clear();
            // Nontransactional sequence consumption is deliberately outside row fingerprints.
            check(fingerprint().equals(baseline),"Every owned row must be restored and every foreign row must remain unchanged");
            check(unique,"Each pre-dispatch owner/message key must identify at most one order"); released(pool);
        });
    }

    @AfterAll
    void closeOnlyTheOwnedPool() { check(!unresolvedWorkers,"Unsettled requests forbid pool shutdown"); if (pool!=null) { pool.close(); check(pool.isClosed(),"The owned application pool must close"); } }

    @Test
    void givenTwoSkusAndVerifiedBuyer_whenManagingCartCheckingOutAndReadingOwnOrder_thenCommitSnapshotsStockSearchEventsAndClearOnlyOwnCart() {
        http(pool,(mvc,context)->{
            check(send(mvc,"GET",CART,token(BUYER),null,200).isEmpty(),"A new Cart must be empty");
            send(mvc,"POST",CART+"/items",token(BUYER),Map.of("skuId",NORMAL,"quantity",2,"userId",990001),200);
            send(mvc,"POST",CART+"/items",token(BUYER),Map.of("skuId",73001,"quantity",1),200);
            send(mvc,"PATCH",CART+"/items/"+NORMAL+"?quantity=3",token(BUYER),null,200);
            send(mvc,"PATCH",CART+"/items/"+NORMAL+"?quantity=2",token(BUYER),null,200);
            send(mvc,"DELETE",CART+"/items/73001",token(BUYER),null,200);
            send(mvc,"DELETE",CART,token(BUYER),null,200);
            send(mvc,"POST",CART+"/items",token(BUYER),Map.of("skuId",NORMAL,"quantity",2),200);
            send(mvc,"POST",CART+"/items",token(BUYER),Map.of("skuId",73001,"quantity",1),200);
            OwnedBody request=body(BUYER,NORMAL,2);request.value().put("userId",990001L);
            jdbc.update("UPDATE pawbridge_store.product_skus SET price=2500 WHERE id=?",NORMAL);
            JsonNode created=send(mvc,"POST",CREATE,token(BUYER),request.value(),201);long id=remember(created,request.owner());
            check(created.path("userId").asLong()==BUYER&&created.path("totalAmount").asLong()==6000
                    &&created.path("status").asText().equals("PENDING")&&created.path("deliveryStatus").asText().equals("READY")
                    &&created.path("items").size()==2,"Checkout must use locked current prices and original order defaults");
            UUID.fromString(created.path("orderUuid").asText());
            check(stock(NORMAL)==6&&stock(73001)==4&&count("SELECT count(*) FROM pawbridge_store.orders WHERE user_id=910001")==1
                    &&count("SELECT count(*) FROM pawbridge_store.order_items WHERE order_id="+id)==2,"Both SKU quantities and a single order must commit together");
            check(jdbc.queryForObject("SELECT price FROM pawbridge_store.order_items WHERE order_id=? AND product_sku_id=?",Long.class,id,NORMAL)==2500,
                    "Purchased item must retain the locked price snapshot");
            check(count("SELECT count(*) FROM pawbridge_store.product_search_documents WHERE product_id=71001 AND analyzer_version<>'owned-fixture-v1'")==1,
                    "The selected product search projection must commit in the same writer transaction");
            check(events(73001,73002).isEmpty(),"The SKU_UPDATED outbox was removed; checkout must not write Store outbox rows");
            check(send(mvc,"GET",CART,token(BUYER),null,200).isEmpty(),"Checkout must consume the entire buyer Cart");
            jdbc.update("UPDATE pawbridge_store.product_skus SET price=2700 WHERE id=?",NORMAL);
            JsonNode own=send(mvc,"GET",GET+id,token(BUYER),null,200);
            check(sameOrder(created,own,id),"Own GET must retain the complete purchase snapshot after live price changes");
            send(mvc,"GET",GET+id,token(OTHER),null,400);
            send(mvc,"GET",GET+FOREIGN_ORDER,token(BUYER),null,400);
            for(Long user:List.of(PENDING,910099L))send(mvc,"POST",CREATE,token(user),body(user,NORMAL,1).value(),403);
            send(mvc,"POST",CREATE,null,request.value(),401);
            send(mvc,"POST","/api/v1/orders/direct",token(BUYER),request.value(),403);
            check(count("SELECT quantity FROM pawbridge_store.cart_items WHERE id=990041")==3,"Foreign Cart must remain untouched");
        });
    }

    @Test
    void givenDeferredCartClearFailureAndStaleItems_whenCheckingOutThenRetrying_thenRollbackEveryTableAndPreserveMissingOnlyCart() {
        http(pool,(mvc,context)->{
            send(mvc,"POST",CART+"/items",token(BUYER),Map.of("skuId",NORMAL,"quantity",1),200);
            OwnedBody failed=body(BUYER,NORMAL,1);failed.value().put("deliveryMessage","OWNED-P12-FAIL-CLEAR-"+failed.owner().key());
            requests.remove(failed.owner());Ownership failureOwner=new Ownership(BUYER,NORMAL,(String)failed.value().get("deliveryMessage"));requests.add(failureOwner);
            Map<String,Fingerprint> before=fingerprint();
            send(mvc,"POST",CREATE,token(BUYER),failed.value(),500);
            check(fingerprint().equals(before),"Deferred failure after actual Cart item DELETE must roll back Cart, stock, order, outbox and search");released(pool);
            OwnedBody retry=body(BUYER,NORMAL,1);JsonNode recovered=send(mvc,"POST",CREATE,token(BUYER),retry.value(),201);remember(recovered,retry.owner());
            check(send(mvc,"GET",CART,token(BUYER),null,200).isEmpty(),"The same Cart must be consumable after the failed commit");
            send(mvc,"POST",CART+"/items",token(OTHER),Map.of("skuId",NORMAL,"quantity",1),200);
            jdbc.update("UPDATE pawbridge_store.cart_items SET product_sku_id=999999,sku_id=999999 WHERE cart_id=(SELECT id FROM pawbridge_store.carts WHERE user_id=?)",OTHER);
            OwnedBody missing=body(OTHER,NORMAL,1);before=fingerprint();send(mvc,"POST",CREATE,token(OTHER),missing.value(),400);
            check(fingerprint().equals(before),"Missing-only Cart must remain persisted when no purchasable SKU exists");
            send(mvc,"POST",CART+"/items",token(OTHER),Map.of("skuId",NORMAL,"quantity",1),200);
            OwnedBody mixed=body(OTHER,NORMAL,1);JsonNode created=send(mvc,"POST",CREATE,token(OTHER),mixed.value(),201);remember(created,mixed.owner());
            check(created.path("items").size()==1&&send(mvc,"GET",CART,token(OTHER),null,200).isEmpty(),
                    "Mixed stale Cart must order existing SKUs and remove both stale and purchased items");
        });
    }

    @Test
    void givenTwoCheckoutPidsThenAnOrderedCheckoutAndAdd_whenRealLocksAreReleased_thenConsumeOnceAndRetainTheLaterAddition() {
        http(pool,(mvc,context)->{
            send(mvc,"POST",CART+"/items",token(BUYER),Map.of("skuId",NORMAL,"quantity",1),200);
            ExecutorService workers=Executors.newFixedThreadPool(2);unresolvedWorkers=true;
            try {
                OwnedBody first=body(BUYER,NORMAL,1),second=body(BUYER,NORMAL,1);
                Future<HttpResult> a,b;
                try(Connection control=probe.getConnection()){
                    control.setAutoCommit(false);lock(control,"SELECT user_id FROM pawbridge_user.users WHERE user_id=? FOR UPDATE",BUYER);
                    int holder=pid(control);
                    a=workers.submit(()->exchange(mvc,"POST",CREATE,token(BUYER),first.value()));
                    b=workers.submit(()->exchange(mvc,"POST",CREATE,token(BUYER),second.value()));
                    check(waitForBlockerChain(control,holder,2).size()==2,"Two physical checkout PIDs must wait on the buyer row or its blocker chain");
                    control.commit();
                }
                HttpResult ar=a.get(6,TimeUnit.SECONDS),br=b.get(6,TimeUnit.SECONDS);
                check((ar.status()==201&&br.status()==400)||(ar.status()==400&&br.status()==201),"One Cart must be consumed exactly once by two serialized checkouts");
                if(ar.status()==201)remember(ar.body(),first.owner());else remember(br.body(),second.owner());
                check(stock(NORMAL)==7&&count("SELECT count(*) FROM pawbridge_store.orders WHERE user_id=910001")==1,
                        "The losing checkout must not decrement stock or create another order");
                send(mvc,"POST",CART+"/items",token(BUYER),Map.of("skuId",NORMAL,"quantity",1),200);
                OwnedBody ordered=body(BUYER,NORMAL,1);Future<HttpResult> checkout,add;
                try(Connection control=probe.getConnection()){
                    control.setAutoCommit(false);lock(control,"SELECT id FROM pawbridge_store.product_skus WHERE id=? FOR UPDATE",NORMAL);
                    try(var update=control.prepareStatement("UPDATE pawbridge_store.product_skus SET price=3333 WHERE id=?")){update.setLong(1,NORMAL);update.executeUpdate();}
                    int holder=pid(control);
                    checkout=workers.submit(()->exchange(mvc,"POST",CREATE,token(BUYER),ordered.value()));
                    Set<Integer> checkoutPid=waitForBlockerChain(control,holder,1);
                    check(checkoutPid.size()==1,"Checkout must first own the buyer lock and actually wait on the controlled SKU lock");
                    add=workers.submit(()->exchange(mvc,"POST",CART+"/items",token(BUYER),Map.of("skuId",NORMAL,"quantity",2)));
                    Set<Integer> both=waitForBlockerChain(control,holder,2);
                    check(both.size()==2&&both.containsAll(checkoutPid),"The later Cart add must wait behind the already blocked checkout");
                    control.commit();
                }
                HttpResult created=checkout.get(6,TimeUnit.SECONDS);check(created.status()==201&&add.get(6,TimeUnit.SECONDS).status()==200,
                        "Ordered checkout and subsequent add must both succeed");remember(created.body(),ordered.owner());
                check(created.body().path("totalAmount").asLong()==3333&&created.body().path("items").get(0).path("price").asLong()==3333,
                        "Checkout must use the price committed by the SKU lock holder rather than a pre-lock entity snapshot");
                JsonNode cart=send(mvc,"GET",CART,token(BUYER),null,200);
                check(cart.size()==1&&cart.get(0).path("skuId").asLong()==NORMAL&&cart.get(0).path("quantity").asInt()==2
                        &&stock(NORMAL)==6&&count("SELECT count(*) FROM pawbridge_store.orders WHERE user_id=910001")==2,
                        "The addition ordered after consumption must survive in the new Cart state");
            } finally {
                workers.shutdown();boolean settled=false;
                try {
                    settled=workers.awaitTermination(8,TimeUnit.SECONDS);
                    if(!settled){workers.shutdownNow();settled=workers.awaitTermination(3,TimeUnit.SECONDS);}
                } catch(InterruptedException interrupted) {
                    workers.shutdownNow();Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted owned workers must remain fenced from cleanup");
                } finally { unresolvedWorkers=!settled; }
                check(settled,"Workers must settle before row cleanup, pool reset or close");
            }
            released(pool);
        });
    }

    private static void lock(Connection c,String sql,long id)throws Exception{try(var s=c.prepareStatement(sql)){s.setLong(1,id);s.setQueryTimeout(3);s.execute();}}
    private static int pid(Connection c)throws Exception{try(var s=c.prepareStatement("SELECT pg_backend_pid()")){try(var rows=s.executeQuery()){rows.next();return rows.getInt(1);}}}
    private static Set<Integer> waitForBlockerChain(Connection c,int holder,int minimum)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        do{
            try(var s=c.prepareStatement("SELECT pg_stat_clear_snapshot()")){s.execute();}
            Set<Integer> pids=new LinkedHashSet<>();
            try(var s=c.prepareStatement("SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND pid<>pg_backend_pid() AND state='active' AND wait_event_type='Lock' AND (?=ANY(pg_blocking_pids(pid)) OR EXISTS(SELECT 1 FROM unnest(pg_blocking_pids(pid)) b WHERE ?=ANY(pg_blocking_pids(b))))")){
                s.setInt(1,holder);s.setInt(2,holder);s.setQueryTimeout(2);try(var rows=s.executeQuery()){while(rows.next())pids.add(rows.getInt(1));}
            }
            if(pids.size()>=minimum)return pids;Thread.sleep(20);
        }while(System.nanoTime()<until);
        throw new AssertionError("Owned requests did not reach their controlled database blocker chain");
    }

    private void http(HikariDataSource source,HttpCheck action) {
        observed("actual secured order MVC/JPA", () -> {
            AtomicReference<EntityManagerFactory> factory=new AtomicReference<>();
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
                    JacksonAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class,ValidationAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,WebMvcAutoConfiguration.class,SecurityAutoConfiguration.class,SecurityFilterAutoConfiguration.class))
                    .withUserConfiguration(StoreCartConfiguration.class,ReadSecurityConfiguration.class)
                    .withPropertyValues("pawbridge.store-carts.enabled=true","pawbridge.store-direct-orders.enabled=false","spring.sql.init.mode=never","spring.jmx.enabled=false")
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
    private OwnedBody body(long user,long sku,int quantity) {
        Ownership owner=new Ownership(user,sku,"OWNED-P12-"+UUID.randomUUID()); requests.add(owner);
        Map<String,Object> value=new LinkedHashMap<>();
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
        var request=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),path).servletPath(path.split("\\?",2)[0]).contentType(MediaType.APPLICATION_JSON)
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
        Map<String,JsonNode> firstItems=new LinkedHashMap<>(),secondItems=new LinkedHashMap<>();
        first.path("items").forEach(item->firstItems.put(item.path("skuCode").asText(),item));
        second.path("items").forEach(item->secondItems.put(item.path("skuCode").asText(),item));
        first.remove("items");second.remove("items");
        return stored.getNano()%1000==0 && createDifference.compareTo(Duration.ofNanos(1000))<0
                && loadedAudit.equals(stored) && first.equals(second) && firstItems.equals(secondItems);
    }
    private long count(String sql) { return jdbc.queryForObject(sql,Long.class); }
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
    private record Ownership(long user,long sku,String key) { }
    private record OwnedBody(Ownership owner,Map<String,Object> value) { }
    private record HttpResult(int status,JsonNode body) { }
    private record Fingerprint(long count,String digest) { }
    private record Settings(String jdbcUrl,String database,String username,String password,String marker) {
        static Settings load() throws Exception {
            String path=System.getenv("P12_PG_SETTINGS"); check(path!=null && !path.isBlank(),"P12_PG_SETTINGS is mandatory; owned tests must not skip");
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
            check(database.matches("pb_cart_order_owned_[0-9a-f]{12}") && url.equals("jdbc:postgresql://127.0.0.1:52620/"+database),"Only the exact owned loopback database target is allowed");
            check(username.equals("pb_cart_order_writer") && marker.matches("[0-9a-f]{32}"),"Writer and marker must be explicit");
            return new Settings(url,database,username,required(values,"password"),marker);
        }
        private static String required(Properties values,String name) { String value=values.getProperty(name); check(value!=null && !value.isBlank(),"Every owned setting is required"); return value; }
        @Override public String toString() { return "OwnedDirectOrderSettings[redacted]"; }
    }
}
