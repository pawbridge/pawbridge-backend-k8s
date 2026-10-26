package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.pawbridge.backend.boot.security.PostTokenAuthenticator
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.sql.Connection
import java.time.Duration
import java.time.LocalDateTime
import java.util.Calendar
import java.util.Date
import java.util.Properties
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.crypto.SecretKey
import javax.sql.DataSource

/** Secured Cart-to-checkout HTTP/JPA against a fresh owned P12 fixture; no external payment or source JPA oracle claim. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class OwnedPostgresqlStoreCartOrderTest {
    private val requests: MutableList<Ownership> = ArrayList()
    private val responseIds: MutableSet<Long> = LinkedHashSet()
    private val originalSkus: MutableMap<Long, Map<String, Any?>> = LinkedHashMap()
    private val originalProducts: MutableMap<Long, Map<String, Any?>> = LinkedHashMap()
    private val originalDocuments: MutableMap<Long, Map<String, Any?>> = LinkedHashMap()
    private lateinit var settings: Settings
    private var pool: HikariDataSource? = null
    private lateinit var probe: DriverManagerDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var baseline: Map<String, Fingerprint>
    private var unresolvedWorkers = false
    private val cartUsers: MutableSet<Long> = LinkedHashSet()

    private fun pool(): HikariDataSource = pool!!

    @BeforeAll
    fun verifyOwnedFixtureBeforeOpeningTheApplicationPool() {
        observed("owned writer identity") {
            settings = Settings.load()
            probe = DriverManagerDataSource(settings.jdbcUrl, settings.username, settings.password)
            val timeouts = Properties()
            timeouts.setProperty("connectTimeout", "3")
            timeouts.setProperty("socketTimeout", "5")
            probe.setConnectionProperties(timeouts)
            verifyServer(JdbcTemplate(probe), settings)
            val owned = newPool("owned-direct-order-writer")
            pool = owned
            jdbc = JdbcTemplate(owned)
            jdbc.queryTimeout = 5
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pawbridge_store.orders WHERE user_id IN (910001,910002,910003,910099)",
                    Long::class.javaObjectType,
                ) == 0L,
                "Previous trial orders must not enter a new baseline",
            )
            for (sku in SKU_PRODUCTS.keys) {
                originalSkus[sku] = jdbc.queryForMap("SELECT * FROM pawbridge_store.product_skus WHERE id=?", sku)
                check(
                    count(
                        "SELECT count(*) FROM pawbridge_store.outbox WHERE aggregate_type='product-sku' AND aggregate_id=?",
                        sku.toString(),
                    ) == 0L,
                    "Previous trial SKU events must not enter a new baseline",
                )
            }
            for (product in HashSet(SKU_PRODUCTS.values)) {
                originalProducts[product] = jdbc.queryForMap("SELECT * FROM pawbridge_store.products WHERE id=?", product)
                originalDocuments[product] = jdbc.queryForMap(
                    "SELECT *,tokens::text AS token_text FROM pawbridge_store.product_search_documents WHERE product_id=?",
                    product,
                )
            }
            check(
                count("SELECT count(*) FROM pawbridge_store.carts WHERE user_id IN (910001,910002,910003,910099)") == 0L,
                "Previous owned Cart rows must not enter the baseline",
            )
            baseline = fingerprint()
            released(owned)
        }
    }

    @BeforeEach
    fun requireThePreservedForeignBaseline() {
        observed("case baseline") {
            check(
                !unresolvedWorkers && cartUsers.isEmpty() && requests.isEmpty() && responseIds.isEmpty() &&
                    fingerprint() == baseline,
                "Each case must start from the exact fifteen-table baseline",
            )
            cartUsers.addAll(listOf(BUYER, OTHER, PENDING, 910099L))
        }
    }

    @AfterEach
    fun cleanOnlyPreDispatchOwnedOrdersAndTouchedFixtureProducts() {
        observed("owned cleanup") {
            check(!unresolvedWorkers, "Unsettled requests forbid row cleanup")
            released(pool())
            val ids: MutableSet<Long> = LinkedHashSet(responseIds)
            val products: MutableSet<Long> = LinkedHashSet()
            var unique = true
            for (ownership in requests) {
                val found = orderIds(ownership)
                unique = unique and (found.size <= 1)
                ids.addAll(found)
                if (SKU_PRODUCTS.containsKey(ownership.sku)) {
                    products.add(SKU_PRODUCTS.getValue(ownership.sku))
                }
            }
            for (id in ids) {
                check(id > 0 && id != FOREIGN_ORDER, "Cleanup cannot select a foreign order")
            }
            for (id in ids) {
                jdbc.update("DELETE FROM pawbridge_store.order_items WHERE order_id=?", id)
                jdbc.update("DELETE FROM pawbridge_store.orders WHERE id=?", id)
            }
            for (entry in SKU_PRODUCTS.entries) {
                if (products.contains(entry.value)) {
                    val sku = entry.key
                    val value = originalSkus.getValue(sku)
                    jdbc.update(
                        "UPDATE pawbridge_store.product_skus SET price=?,stock_quantity=?,created_at=?,updated_at=? WHERE id=?",
                        value["price"],
                        value["stock_quantity"],
                        value["created_at"],
                        value["updated_at"],
                        sku,
                    )
                }
            }
            for (product in products) {
                val value = originalProducts.getValue(product)
                val document = originalDocuments.getValue(product)
                jdbc.update(
                    "UPDATE pawbridge_store.products SET name=?,status=?,created_at=?,updated_at=? WHERE id=?",
                    value["name"],
                    value["status"],
                    value["created_at"],
                    value["updated_at"],
                    product,
                )
                jdbc.update(
                    "INSERT INTO pawbridge_store.product_search_documents(product_id,sku_id,source_name,source_option,analyzer_version,tokens) " +
                        "VALUES (?,?,?,?,?,?::tsvector) ON CONFLICT(product_id) DO UPDATE SET sku_id=excluded.sku_id,source_name=excluded.source_name," +
                        "source_option=excluded.source_option,analyzer_version=excluded.analyzer_version,tokens=excluded.tokens",
                    product,
                    document["sku_id"],
                    document["source_name"],
                    document["source_option"],
                    document["analyzer_version"],
                    document["token_text"],
                )
            }
            for (user in cartUsers) {
                check(
                    user == BUYER || user == OTHER || user == PENDING || user == 910099L,
                    "Cart cleanup must use pre-dispatch owned users only",
                )
                jdbc.update(
                    "DELETE FROM pawbridge_store.cart_items WHERE cart_id IN (SELECT id FROM pawbridge_store.carts WHERE user_id=?)",
                    user,
                )
                jdbc.update("DELETE FROM pawbridge_store.carts WHERE user_id=?", user)
            }
            cartUsers.clear()
            requests.clear()
            responseIds.clear()
            // Nontransactional sequence consumption is deliberately outside row fingerprints.
            check(fingerprint() == baseline, "Every owned row must be restored and every foreign row must remain unchanged")
            check(unique, "Each pre-dispatch owner/message key must identify at most one order")
            released(pool())
        }
    }

    @AfterAll
    fun closeOnlyTheOwnedPool() {
        check(!unresolvedWorkers, "Unsettled requests forbid pool shutdown")
        val owned = pool
        if (owned != null) {
            owned.close()
            check(owned.isClosed, "The owned application pool must close")
        }
    }

    @Test
    fun `상품 둘과 검증된 구매자가 주어졌을 때__장바구니를 관리하고 결제해 주문을 읽으면__스냅샷·재고·검색·이벤트를 커밋하고 자기 장바구니만 비운다`() {
        http(pool()) { mvc, _ ->
            check(send(mvc, "GET", CART, token(BUYER), null, 200).isEmpty, "A new Cart must be empty")
            send(mvc, "POST", CART + "/items", token(BUYER), mapOf("skuId" to NORMAL, "quantity" to 2, "userId" to 990001), 200)
            send(mvc, "POST", CART + "/items", token(BUYER), mapOf("skuId" to 73001, "quantity" to 1), 200)
            send(mvc, "PATCH", CART + "/items/" + NORMAL + "?quantity=3", token(BUYER), null, 200)
            send(mvc, "PATCH", CART + "/items/" + NORMAL + "?quantity=2", token(BUYER), null, 200)
            send(mvc, "DELETE", CART + "/items/73001", token(BUYER), null, 200)
            send(mvc, "DELETE", CART, token(BUYER), null, 200)
            send(mvc, "POST", CART + "/items", token(BUYER), mapOf("skuId" to NORMAL, "quantity" to 2), 200)
            send(mvc, "POST", CART + "/items", token(BUYER), mapOf("skuId" to 73001, "quantity" to 1), 200)
            val request = body(BUYER, NORMAL, 2)
            request.value["userId"] = 990001L
            jdbc.update("UPDATE pawbridge_store.product_skus SET price=2500 WHERE id=?", NORMAL)
            val created = send(mvc, "POST", CREATE, token(BUYER), request.value, 201)
            val id = remember(created, request.owner)
            check(
                created.path("userId").asLong() == BUYER && created.path("totalAmount").asLong() == 6000L &&
                    created.path("status").asText() == "PENDING" && created.path("deliveryStatus").asText() == "READY" &&
                    created.path("items").size() == 2,
                "Checkout must use locked current prices and original order defaults",
            )
            UUID.fromString(created.path("orderUuid").asText())
            check(
                stock(NORMAL) == 6 && stock(73001) == 4 &&
                    count("SELECT count(*) FROM pawbridge_store.orders WHERE user_id=910001") == 1L &&
                    count("SELECT count(*) FROM pawbridge_store.order_items WHERE order_id=" + id) == 2L,
                "Both SKU quantities and a single order must commit together",
            )
            check(
                jdbc.queryForObject(
                    "SELECT price FROM pawbridge_store.order_items WHERE order_id=? AND product_sku_id=?",
                    Long::class.javaObjectType,
                    id,
                    NORMAL,
                ) == 2500L,
                "Purchased item must retain the locked price snapshot",
            )
            check(
                count(
                    "SELECT count(*) FROM pawbridge_store.product_search_documents WHERE product_id=71001 AND analyzer_version<>'owned-fixture-v1'",
                ) == 1L,
                "The selected product search projection must commit in the same writer transaction",
            )
            check(events(73001, 73002).isEmpty(), "The SKU_UPDATED outbox was removed; checkout must not write Store outbox rows")
            check(send(mvc, "GET", CART, token(BUYER), null, 200).isEmpty, "Checkout must consume the entire buyer Cart")
            jdbc.update("UPDATE pawbridge_store.product_skus SET price=2700 WHERE id=?", NORMAL)
            val own = send(mvc, "GET", GET + id, token(BUYER), null, 200)
            check(sameOrder(created, own, id), "Own GET must retain the complete purchase snapshot after live price changes")
            send(mvc, "GET", GET + id, token(OTHER), null, 400)
            send(mvc, "GET", GET + FOREIGN_ORDER, token(BUYER), null, 400)
            for (user in listOf(PENDING, 910099L)) {
                send(mvc, "POST", CREATE, token(user), body(user, NORMAL, 1).value, 403)
            }
            send(mvc, "POST", CREATE, null, request.value, 401)
            send(mvc, "POST", "/api/v1/orders/direct", token(BUYER), request.value, 403)
            check(
                count("SELECT quantity FROM pawbridge_store.cart_items WHERE id=990041") == 3L,
                "Foreign Cart must remain untouched",
            )
        }
    }

    @Test
    fun `지연된 장바구니 비우기 실패와 오래된 항목이 주어졌을 때__결제 후 재시도하면__모든 테이블을 롤백하고 누락 전용 장바구니를 보존한다`() {
        http(pool()) { mvc, _ ->
            send(mvc, "POST", CART + "/items", token(BUYER), mapOf("skuId" to NORMAL, "quantity" to 1), 200)
            val failed = body(BUYER, NORMAL, 1)
            failed.value["deliveryMessage"] = "OWNED-P12-FAIL-CLEAR-" + failed.owner.key
            requests.remove(failed.owner)
            val failureOwner = Ownership(BUYER, NORMAL, failed.value["deliveryMessage"] as String)
            requests.add(failureOwner)
            var before = fingerprint()
            send(mvc, "POST", CREATE, token(BUYER), failed.value, 500)
            check(
                fingerprint() == before,
                "Deferred failure after actual Cart item DELETE must roll back Cart, stock, order, outbox and search",
            )
            released(pool())
            val retry = body(BUYER, NORMAL, 1)
            val recovered = send(mvc, "POST", CREATE, token(BUYER), retry.value, 201)
            remember(recovered, retry.owner)
            check(
                send(mvc, "GET", CART, token(BUYER), null, 200).isEmpty,
                "The same Cart must be consumable after the failed commit",
            )
            send(mvc, "POST", CART + "/items", token(OTHER), mapOf("skuId" to NORMAL, "quantity" to 1), 200)
            jdbc.update(
                "UPDATE pawbridge_store.cart_items SET product_sku_id=999999,sku_id=999999 WHERE cart_id=(SELECT id FROM pawbridge_store.carts WHERE user_id=?)",
                OTHER,
            )
            val missing = body(OTHER, NORMAL, 1)
            before = fingerprint()
            send(mvc, "POST", CREATE, token(OTHER), missing.value, 400)
            check(
                fingerprint() == before,
                "Missing-only Cart must remain persisted when no purchasable SKU exists",
            )
            send(mvc, "POST", CART + "/items", token(OTHER), mapOf("skuId" to NORMAL, "quantity" to 1), 200)
            val mixed = body(OTHER, NORMAL, 1)
            val created = send(mvc, "POST", CREATE, token(OTHER), mixed.value, 201)
            remember(created, mixed.owner)
            check(
                created.path("items").size() == 1 && send(mvc, "GET", CART, token(OTHER), null, 200).isEmpty,
                "Mixed stale Cart must order existing SKUs and remove both stale and purchased items",
            )
        }
    }

    @Test
    fun `결제 PID 둘과 순서 있는 결제·추가가 주어졌을 때__실제 잠금이 풀리면__한 번만 소비하고 나중 추가분을 남긴다`() {
        http(pool()) { mvc, _ ->
            send(mvc, "POST", CART + "/items", token(BUYER), mapOf("skuId" to NORMAL, "quantity" to 1), 200)
            val workers = Executors.newFixedThreadPool(2)
            unresolvedWorkers = true
            try {
                val first = body(BUYER, NORMAL, 1)
                val second = body(BUYER, NORMAL, 1)
                lateinit var a: Future<HttpResult>
                lateinit var b: Future<HttpResult>
                probe.connection.use { control ->
                    control.autoCommit = false
                    lock(control, "SELECT user_id FROM pawbridge_user.users WHERE user_id=? FOR UPDATE", BUYER)
                    val holder = pid(control)
                    a = workers.submit(Callable { exchange(mvc, "POST", CREATE, token(BUYER), first.value) })
                    b = workers.submit(Callable { exchange(mvc, "POST", CREATE, token(BUYER), second.value) })
                    check(
                        waitForBlockerChain(control, holder, 2).size == 2,
                        "Two physical checkout PIDs must wait on the buyer row or its blocker chain",
                    )
                    control.commit()
                }
                val ar = a.get(6, TimeUnit.SECONDS)
                val br = b.get(6, TimeUnit.SECONDS)
                check(
                    (ar.status == 201 && br.status == 400) || (ar.status == 400 && br.status == 201),
                    "One Cart must be consumed exactly once by two serialized checkouts",
                )
                if (ar.status == 201) {
                    remember(ar.body, first.owner)
                } else {
                    remember(br.body, second.owner)
                }
                check(
                    stock(NORMAL) == 7 &&
                        count("SELECT count(*) FROM pawbridge_store.orders WHERE user_id=910001") == 1L,
                    "The losing checkout must not decrement stock or create another order",
                )
                send(mvc, "POST", CART + "/items", token(BUYER), mapOf("skuId" to NORMAL, "quantity" to 1), 200)
                val ordered = body(BUYER, NORMAL, 1)
                lateinit var checkout: Future<HttpResult>
                lateinit var add: Future<HttpResult>
                probe.connection.use { control ->
                    control.autoCommit = false
                    lock(control, "SELECT id FROM pawbridge_store.product_skus WHERE id=? FOR UPDATE", NORMAL)
                    control.prepareStatement("UPDATE pawbridge_store.product_skus SET price=3333 WHERE id=?").use { update ->
                        update.setLong(1, NORMAL)
                        update.executeUpdate()
                    }
                    val holder = pid(control)
                    checkout = workers.submit(Callable { exchange(mvc, "POST", CREATE, token(BUYER), ordered.value) })
                    val checkoutPid = waitForBlockerChain(control, holder, 1)
                    check(
                        checkoutPid.size == 1,
                        "Checkout must first own the buyer lock and actually wait on the controlled SKU lock",
                    )
                    add = workers.submit(
                        Callable {
                            exchange(mvc, "POST", CART + "/items", token(BUYER), mapOf("skuId" to NORMAL, "quantity" to 2))
                        },
                    )
                    val both = waitForBlockerChain(control, holder, 2)
                    check(
                        both.size == 2 && both.containsAll(checkoutPid),
                        "The later Cart add must wait behind the already blocked checkout",
                    )
                    control.commit()
                }
                val created = checkout.get(6, TimeUnit.SECONDS)
                check(
                    created.status == 201 && add.get(6, TimeUnit.SECONDS).status == 200,
                    "Ordered checkout and subsequent add must both succeed",
                )
                remember(created.body, ordered.owner)
                check(
                    created.body.path("totalAmount").asLong() == 3333L &&
                        created.body.path("items").get(0).path("price").asLong() == 3333L,
                    "Checkout must use the price committed by the SKU lock holder rather than a pre-lock entity snapshot",
                )
                val cart = send(mvc, "GET", CART, token(BUYER), null, 200)
                check(
                    cart.size() == 1 && cart.get(0).path("skuId").asLong() == NORMAL &&
                        cart.get(0).path("quantity").asInt() == 2 &&
                        stock(NORMAL) == 6 &&
                        count("SELECT count(*) FROM pawbridge_store.orders WHERE user_id=910001") == 2L,
                    "The addition ordered after consumption must survive in the new Cart state",
                )
            } finally {
                workers.shutdown()
                var settled = false
                try {
                    settled = workers.awaitTermination(8, TimeUnit.SECONDS)
                    if (!settled) {
                        workers.shutdownNow()
                        settled = workers.awaitTermination(3, TimeUnit.SECONDS)
                    }
                } catch (interrupted: InterruptedException) {
                    workers.shutdownNow()
                    Thread.currentThread().interrupt()
                    throw AssertionError("Interrupted owned workers must remain fenced from cleanup")
                } finally {
                    unresolvedWorkers = !settled
                }
                check(settled, "Workers must settle before row cleanup, pool reset or close")
            }
            released(pool())
        }
    }

    private fun http(source: HikariDataSource, action: HttpCheck) {
        observed("actual secured order MVC/JPA") {
            val factory = AtomicReference<EntityManagerFactory>()
            WebApplicationContextRunner()
                .withConfiguration(
                    AutoConfigurations.of(
                        PropertyPlaceholderAutoConfiguration::class.java,
                        JacksonAutoConfiguration::class.java,
                        HttpMessageConvertersAutoConfiguration::class.java,
                        ValidationAutoConfiguration::class.java,
                        DispatcherServletAutoConfiguration::class.java,
                        WebMvcAutoConfiguration::class.java,
                        SecurityAutoConfiguration::class.java,
                        SecurityFilterAutoConfiguration::class.java,
                    ),
                )
                .withUserConfiguration(StoreCartConfiguration::class.java, ReadSecurityConfiguration::class.java)
                .withPropertyValues(
                    "pawbridge.store-carts.enabled=true",
                    "pawbridge.store-direct-orders.enabled=false",
                    "spring.sql.init.mode=never",
                    "spring.jmx.enabled=false",
                )
                .withBean(
                    "writeDataSource",
                    DataSource::class.java,
                    Supplier { source },
                    BeanDefinitionCustomizer { definition -> definition.setDestroyMethodName("") },
                )
                .withBean(
                    "writeJdbc",
                    JdbcTemplate::class.java,
                    Supplier {
                        val selected = JdbcTemplate(source)
                        selected.queryTimeout = 5
                        selected
                    },
                )
                .withBean(PostTokenAuthenticator::class.java, Supplier { PostTokenAuthenticator(KEY) })
                .run { context ->
                    check(context.startupFailure == null, "The real Store order and security configuration must start")
                    factory.set(context.getBean("storeDirectOrderEntityManagerFactory", EntityManagerFactory::class.java))
                    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context)
                        .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
                        .build()
                    try {
                        action.run(mvc, context)
                    } catch (failure: AssertionError) {
                        throw failure
                    } catch (failure: Exception) {
                        throw AssertionError("Owned order HTTP observation failed: " + failure.javaClass.name)
                    }
                }
            check(
                factory.get() != null && !factory.get().isOpen,
                "The owned context must close its Store EntityManagerFactory",
            )
            check(!source.isClosed, "Context close must retain the external owned pool until its owner closes it")
            released(source)
        }
    }

    private fun newPool(name: String): HikariDataSource {
        val config = HikariConfig()
        config.jdbcUrl = settings.jdbcUrl
        config.username = settings.username
        config.password = settings.password
        config.poolName = name
        config.maximumPoolSize = 2
        config.minimumIdle = 0
        config.connectionTimeout = 2000
        config.validationTimeout = 1000
        config.initializationFailTimeout = -1
        config.isReadOnly = false
        config.addDataSourceProperty("connectTimeout", "3")
        config.addDataSourceProperty("socketTimeout", "10")
        return HikariDataSource(config)
    }

    private fun body(user: Long, sku: Long, quantity: Int): OwnedBody {
        val owner = Ownership(user, sku, "OWNED-P12-" + UUID.randomUUID())
        requests.add(owner)
        val value = LinkedHashMap<String, Any?>()
        value["receiverName"] = "synthetic receiver"
        value["receiverPhone"] = "0000000000"
        value["deliveryAddress"] = "synthetic address"
        value["deliveryMessage"] = owner.key
        return OwnedBody(owner, value)
    }

    private fun remember(result: JsonNode, owner: Ownership): Long {
        val id = result.path("orderId").asLong()
        val observed = orderIds(owner)
        check(
            id > 0 && id != FOREIGN_ORDER && observed.size == 1 && observed.first() == id &&
                result.path("userId").asLong() == owner.user && owner.key == result.path("deliveryMessage").asText(),
            "Response identity must match the pre-dispatch User and exact request ownership key",
        )
        responseIds.add(id)
        return id
    }

    private fun orderIds(owner: Ownership): List<Long> {
        return jdbc.query(
            "SELECT id FROM pawbridge_store.orders WHERE user_id=? AND delivery_message=?",
            RowMapper { row, _ -> row.getLong(1) },
            owner.user,
            owner.key,
        )
    }

    private fun sameOrder(created: JsonNode, loaded: JsonNode, id: Long): Boolean {
        // Match the actual Root Hibernate JDBC UTC binding when reading TIMESTAMP(6).
        // Create maps before commit, but GET must equal the stored audit value exactly.
        val stored: LocalDateTime = jdbc.queryForObject(
            "SELECT created_at FROM pawbridge_store.orders WHERE id=?",
            RowMapper<LocalDateTime> { row, _ ->
                row.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toLocalDateTime()
            },
            id,
        )!!
        val createDifference = Duration.between(LocalDateTime.parse(created.path("createdAt").asText()), stored).abs()
        val loadedAudit = LocalDateTime.parse(loaded.path("createdAt").asText())
        val first = created.deepCopy<ObjectNode>()
        val second = loaded.deepCopy<ObjectNode>()
        first.remove("createdAt")
        second.remove("createdAt")
        val firstItems: MutableMap<String, JsonNode> = LinkedHashMap()
        val secondItems: MutableMap<String, JsonNode> = LinkedHashMap()
        first.path("items").forEach { item -> firstItems[item.path("skuCode").asText()] = item }
        second.path("items").forEach { item -> secondItems[item.path("skuCode").asText()] = item }
        first.remove("items")
        second.remove("items")
        return stored.nano % 1000 == 0 && createDifference.compareTo(Duration.ofNanos(1000)) < 0 &&
            loadedAudit == stored && first == second && firstItems == secondItems
    }

    private fun count(sql: String): Long {
        return jdbc.queryForObject(sql, Long::class.javaObjectType)!!
    }

    private fun stock(sku: Long): Int {
        return jdbc.queryForObject(
            "SELECT stock_quantity FROM pawbridge_store.product_skus WHERE id=?",
            Int::class.javaObjectType,
            sku,
        )!!
    }

    private fun count(sql: String, value: Any): Long {
        return jdbc.queryForObject(sql, Long::class.javaObjectType, value)!!
    }

    private fun events(vararg skus: Long): List<Map<String, Any?>> {
        val result: MutableList<Map<String, Any?>> = ArrayList()
        for (sku in skus) {
            result.addAll(
                jdbc.queryForList(
                    "SELECT * FROM pawbridge_store.outbox WHERE aggregate_type='product-sku' AND aggregate_id=? ORDER BY id",
                    sku.toString(),
                ),
            )
        }
        return result
    }

    private fun fingerprint(): Map<String, Fingerprint> {
        val state = LinkedHashMap<String, Fingerprint>()
        TABLE_KEYS.forEach { (table, key) ->
            val value = jdbc.queryForMap(
                "SELECT count(*) AS n,md5(COALESCE(string_agg(row_to_json(owned_row)::text,',' ORDER BY " + key +
                    "),'')) AS digest FROM " + table + " owned_row",
            )
            state[table] = Fingerprint((value["n"] as Number).toLong(), value["digest"] as String)
        }
        return state
    }

    private fun interface Checked {
        fun run()
    }

    private fun interface HttpCheck {
        fun run(mvc: MockMvc, context: ApplicationContext)
    }

    private data class Ownership(val user: Long, val sku: Long, val key: String)

    private class OwnedBody(val owner: Ownership, val value: MutableMap<String, Any?>)

    private class HttpResult(val status: Int, val body: JsonNode)

    private data class Fingerprint(val count: Long, val digest: String)

    private class Settings(
        val jdbcUrl: String,
        val database: String,
        val username: String,
        val password: String,
        val marker: String,
    ) {
        override fun toString(): String = "OwnedDirectOrderSettings[redacted]"

        companion object {
            fun load(): Settings {
                val path = System.getenv("P12_PG_SETTINGS")
                check(path != null && path.isNotBlank(), "P12_PG_SETTINGS is mandatory; owned tests must not skip")
                val file = Path.of(path)
                check(
                    file.isAbsolute && file.normalize() == file &&
                        Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file),
                    "Settings must be an absolute regular non-symlink file",
                )
                check(
                    Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS) ==
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE) &&
                        (Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1,
                    "Settings must have exact0600 permissions and one hard link",
                )
                check(
                    System.getProperty("user.name") == Files.getOwner(file).name &&
                        Files.getOwner(file) == Files.getOwner(file.parent),
                    "Settings and immediate parent must belong to this process owner",
                )
                var parent: Path? = file.parent
                while (parent != null) {
                    check(!Files.isSymbolicLink(parent), "Settings ancestors cannot be symbolic links")
                    parent = parent.parent
                }
                val permissions = Files.getPosixFilePermissions(file.parent, LinkOption.NOFOLLOW_LINKS)
                check(
                    !permissions.contains(PosixFilePermission.GROUP_WRITE) &&
                        !permissions.contains(PosixFilePermission.OTHERS_WRITE),
                    "Settings parent must reject other-user writes",
                )
                val values = Properties()
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input: InputStream -> values.load(input) }
                val database = required(values, "database")
                val url = required(values, "jdbcUrl")
                val username = required(values, "username")
                val marker = required(values, "marker")
                check(
                    database.matches(Regex("pb_cart_order_owned_[0-9a-f]{12}")) &&
                        url == "jdbc:postgresql://127.0.0.1:52620/" + database,
                    "Only the exact owned loopback database target is allowed",
                )
                check(
                    username == "pb_cart_order_writer" && marker.matches(Regex("[0-9a-f]{32}")),
                    "Writer and marker must be explicit",
                )
                return Settings(url, database, username, required(values, "password"), marker)
            }

            private fun required(values: Properties, name: String): String {
                val value = values.getProperty(name)
                check(value != null && value.isNotBlank(), "Every owned setting is required")
                return value
            }
        }
    }

    companion object {
        private const val CREATE = "/api/v1/orders"
        private const val GET = "/api/v1/orders/"
        private const val CART = "/api/v1/carts"
        private const val BUYER = 910001L
        private const val OTHER = 910002L
        private const val PENDING = 910003L
        private const val FOREIGN_ORDER = 990010L
        private const val NORMAL = 73002L
        private val SKU_PRODUCTS: Map<Long, Long> = mapOf(
            73001L to 71001L,
            73002L to 71001L,
            73003L to 71002L,
            73004L to 71003L,
            73005L to 71004L,
            73006L to 71005L,
            73007L to 71006L,
            73008L to 71007L,
        )
        private val TABLE_KEYS: Map<String, String> = mapOf(
            "pawbridge_user.users" to "user_id",
            "pawbridge_user.contact_deletions" to "user_id",
            "pawbridge_store.categories" to "id",
            "pawbridge_store.option_groups" to "id",
            "pawbridge_store.option_values" to "id",
            "pawbridge_store.products" to "id",
            "pawbridge_store.product_skus" to "id",
            "pawbridge_store.sku_values" to "id",
            "pawbridge_store.wishlists" to "id",
            "pawbridge_store.orders" to "id",
            "pawbridge_store.order_items" to "id",
            "pawbridge_store.outbox" to "id",
            "pawbridge_store.product_search_documents" to "product_id",
            "pawbridge_store.carts" to "id",
            "pawbridge_store.cart_items" to "id",
        )
        private val KEY: SecretKey = Keys.hmacShaKeyFor(
            "owned-p9-public-synthetic-signing-key-20261020-only".toByteArray(StandardCharsets.UTF_8),
        )
        private val JSON: ObjectMapper = ObjectMapper().findAndRegisterModules()

        private fun lock(c: Connection, sql: String, id: Long) {
            c.prepareStatement(sql).use { s ->
                s.setLong(1, id)
                s.queryTimeout = 3
                s.execute()
            }
        }

        private fun pid(c: Connection): Int {
            c.prepareStatement("SELECT pg_backend_pid()").use { s ->
                s.executeQuery().use { rows ->
                    rows.next()
                    return rows.getInt(1)
                }
            }
        }

        private fun waitForBlockerChain(c: Connection, holder: Int, minimum: Int): Set<Int> {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            do {
                c.prepareStatement("SELECT pg_stat_clear_snapshot()").use { s -> s.execute() }
                val pids: MutableSet<Int> = LinkedHashSet()
                c.prepareStatement(
                    "SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND pid<>pg_backend_pid() AND state='active' AND wait_event_type='Lock' AND (?=ANY(pg_blocking_pids(pid)) OR EXISTS(SELECT 1 FROM unnest(pg_blocking_pids(pid)) b WHERE ?=ANY(pg_blocking_pids(b))))",
                ).use { s ->
                    s.setInt(1, holder)
                    s.setInt(2, holder)
                    s.queryTimeout = 2
                    s.executeQuery().use { rows ->
                        while (rows.next()) {
                            pids.add(rows.getInt(1))
                        }
                    }
                }
                if (pids.size >= minimum) {
                    return pids
                }
                Thread.sleep(20)
            } while (System.nanoTime() < until)
            throw AssertionError("Owned requests did not reach their controlled database blocker chain")
        }

        private fun token(user: Long): String {
            return "Bearer " + Jwts.builder()
                .claim("userId", user)
                .claim("role", "ROLE_USER")
                .expiration(Date(System.currentTimeMillis() + 300_000))
                .signWith(KEY)
                .compact()
        }

        private fun send(
            mvc: MockMvc,
            method: String,
            path: String,
            authorization: String?,
            body: Map<String, Any?>?,
            expected: Int,
        ): JsonNode {
            val result = exchange(mvc, method, path, authorization, body)
            check(
                result.status == expected,
                "Unexpected Store HTTP status: expected=" + expected + ", actual=" + result.status,
            )
            return result.body
        }

        private fun exchange(
            mvc: MockMvc,
            method: String,
            path: String,
            authorization: String?,
            body: Map<String, Any?>?,
        ): HttpResult {
            val request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path)
                .servletPath(path.split(Regex("\\?"), 2)[0])
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .header("X-User-Id", "990001")
                .header("X-User-Role", "ROLE_ADMIN")
            if (authorization != null) {
                request.header("Authorization", authorization)
            }
            if (body != null) {
                request.content(JSON.writeValueAsString(body))
            }
            val result = mvc.perform(request).andReturn().response
            check(result.getHeader("Set-Cookie") == null, "The order routes must remain stateless")
            val content = result.getContentAsString(StandardCharsets.UTF_8)
            return HttpResult(
                result.status,
                if (content.isEmpty()) JSON.nodeFactory.nullNode() else JSON.readTree(content),
            )
        }

        private fun released(source: HikariDataSource) {
            check(
                !TransactionSynchronizationManager.isActualTransactionActive() &&
                    !TransactionSynchronizationManager.hasResource(source),
                "No order transaction or User binding may escape an operation",
            )
            check(
                source.hikariPoolMXBean.activeConnections == 0 && source.hikariPoolMXBean.threadsAwaitingConnection == 0,
                "Every owned two-slot pool must return its connection after success or rollback",
            )
        }

        private fun verifyServer(jdbc: JdbcTemplate, settings: Settings) {
            jdbc.queryTimeout = 5
            val identity = jdbc.queryForMap(
                "SELECT current_database() AS db,current_user AS actor,session_user AS session,current_setting('server_version_num') AS version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) AS owner",
            )
            check(
                settings.database == identity["db"] && settings.username == identity["actor"] &&
                    settings.username == identity["session"] && "170011" == identity["version"] &&
                    settings.username != identity["owner"],
                "Owned PostgreSQL target and separate writer identity must match",
            )
            val markers = jdbc.query("SELECT marker FROM public.pawbridge_owned_fixture", RowMapper { row, _ -> row.getString(1) })
            check(
                markers.size == 1 && settings.marker == markers.first(),
                "Only the exact isolated fixture marker is accepted",
            )
            check(
                true == jdbc.queryForObject(
                    "SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT pg_has_role(current_user,(SELECT datdba FROM pg_database WHERE datname=current_database()),'MEMBER') FROM pg_roles WHERE rolname=current_user",
                    Boolean::class.javaObjectType,
                ),
                "The writer must have no administrative or owner inheritance",
            )
            for (schema in listOf("public", "pawbridge_store", "pawbridge_user")) {
                check(
                    false == jdbc.queryForObject(
                        "SELECT has_schema_privilege(current_user,?,'CREATE')",
                        Boolean::class.javaObjectType,
                        schema,
                    ),
                    "The writer must not create schema objects",
                )
            }
            for (table in TABLE_KEYS.keys) {
                check(
                    true == jdbc.queryForObject(
                        "SELECT has_table_privilege(current_user,?,'SELECT') AND NOT has_table_privilege(current_user,?,'TRUNCATE,REFERENCES,TRIGGER')",
                        Boolean::class.javaObjectType,
                        table,
                        table,
                    ),
                    "Only selected nonadministrative table permissions may be used",
                )
            }
            check(
                true == jdbc.queryForObject(
                    "SELECT has_column_privilege(current_user,'pawbridge_user.users','user_id','UPDATE') AND NOT has_table_privilege(current_user,'pawbridge_user.users','INSERT,DELETE') AND NOT has_table_privilege(current_user,'pawbridge_user.contact_deletions','INSERT,UPDATE,DELETE')",
                    Boolean::class.javaObjectType,
                ),
                "User permissions must only permit current-state observation and the required row lock",
            )
        }

        private fun observed(phase: String, action: Checked) {
            try {
                action.run()
            } catch (failure: AssertionError) {
                throw failure
            } catch (failure: Throwable) {
                throw AssertionError(
                    "Owned direct order observation failed: " + phase + "; type=" + failure.javaClass.name,
                )
            }
        }

        private fun check(condition: Boolean, fixedMessage: String) {
            if (!condition) {
                throw AssertionError(fixedMessage)
            }
        }
    }
}
