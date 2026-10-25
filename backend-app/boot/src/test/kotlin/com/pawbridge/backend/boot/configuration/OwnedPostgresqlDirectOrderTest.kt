package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.pawbridge.backend.boot.security.PostTokenAuthenticator
import com.pawbridge.backend.store.api.StoreOrderActors
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import org.springframework.transaction.support.TransactionTemplate
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.LocalDateTime
import java.util.Calendar
import java.util.Date
import java.util.Properties
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.crypto.SecretKey
import javax.sql.DataSource

/** Real HTTP/security/JPA against an explicitly owned reduced Store fixture, never an existing database. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class OwnedPostgresqlDirectOrderTest {
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
            baseline = fingerprint()
            released(owned)
        }
    }

    @BeforeEach
    fun requireThePreservedForeignBaseline() {
        observed("case baseline") {
            check(
                requests.isEmpty() && responseIds.isEmpty() && fingerprint() == baseline,
                "Each case must start from the exact thirteen-table baseline",
            )
        }
    }

    @AfterEach
    fun cleanOnlyPreDispatchOwnedOrdersAndTouchedFixtureProducts() {
        observed("owned cleanup") {
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
        val owned = pool
        if (owned != null) {
            owned.close()
            check(owned.isClosed, "The owned application pool must close")
        }
    }

    @Test
    fun `검증된 구매자와 위조 헤더가 주어졌을 때__구매 후 조회하면__대기 스키마와 구매 스냅샷을 보존하고 아웃박스는 없다`() {
        http(pool()) { mvc, _ ->
            val request = body(BUYER, NORMAL, 2)
            request.value["userId"] = 990001L
            val created = send(mvc, "POST", CREATE, token(BUYER), request.value, 201)
            val id = remember(created, request.owner)
            check(
                fields(created) == setOf(
                    "orderId",
                    "orderUuid",
                    "userId",
                    "totalAmount",
                    "status",
                    "deliveryStatus",
                    "receiverName",
                    "receiverPhone",
                    "deliveryAddress",
                    "deliveryMessage",
                    "createdAt",
                    "items",
                ),
                "The complete original order response schema must survive",
            )
            check(
                created.path("userId").asLong() == BUYER && created.path("totalAmount").asLong() == 4000L &&
                    "PENDING" == created.path("status").asText() && "READY" == created.path("deliveryStatus").asText(),
                "Only the verified JWT owner and original PENDING/READY defaults may be stored",
            )
            UUID.fromString(created.path("orderUuid").asText())
            val item = created.path("items").get(0)
            check(
                fields(item) == setOf("productName", "skuCode", "price", "quantity") &&
                    item.path("price").asLong() == 2000L &&
                    item.path("quantity").asInt() == 2 && "OWNED-P9-ORDERED" == item.path("skuCode").asText(),
                "The original purchase item snapshot must be complete",
            )
            val stored = jdbc.queryForMap("SELECT * FROM pawbridge_store.orders WHERE id=?", id)
            check(
                stored["created_at"] != null && stored["updated_at"] != null && !created.path("createdAt").isNull,
                "Actual JPA auditing must populate the persisted order",
            )
            check(stock(NORMAL) == 6 && stock(73001) == 5, "Only the requested SKU quantity must decrease")
            check(events(73001, 73002).isEmpty(), "The SKU_UPDATED outbox was removed; buying must not write Store outbox rows")
            val document = jdbc.queryForMap("SELECT * FROM pawbridge_store.product_search_documents WHERE product_id=71001")
            check(
                (document["sku_id"] as Number).toLong() == 73001L && KoreanSearchTerms.VERSION == document["analyzer_version"],
                "Search must select the same lowest-price representative SKU",
            )
            jdbc.update("UPDATE pawbridge_store.product_skus SET price=3333 WHERE id=?", NORMAL)
            jdbc.update("UPDATE pawbridge_store.products SET name='changed live product' WHERE id=71001")
            val own = send(mvc, "GET", GET + id, token(BUYER), null, 200)
            check(sameOrder(created, own, id), "Own detail must preserve purchase snapshots after live price/name changes")
            val before = fingerprint()
            send(mvc, "GET", GET + id, token(OTHER), null, 400)
            check(fingerprint() == before, "Another current member must not read or mutate this order")
            released(pool())
        }
    }

    @Test
    fun `대기·누락·미인증 회원이 주어졌을 때__주문 경로를 호출하면__위조 신원을 무시하고 아무것도 쓰지 않는다`() {
        http(pool()) { mvc, _ ->
            val before = fingerprint()
            for (user in listOf(PENDING, 910099L)) {
                val request = body(user, NORMAL, 1)
                send(mvc, "POST", CREATE, token(user), request.value, 403)
                send(mvc, "GET", GET + FOREIGN_ORDER, token(user), null, 403)
            }
            send(mvc, "POST", CREATE, null, emptyMap(), 401)
            send(mvc, "GET", GET + FOREIGN_ORDER, "Bearer invalid.synthetic.token", null, 401)
            send(mvc, "POST", "/api/v1/orders/extra", token(BUYER), emptyMap(), 403)
            check(
                fingerprint() == before,
                "DB current member state, JWT authentication and closed neighbors must prevent all writes",
            )
            released(pool())
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [73003, 73004])
    fun `재고 부족이나 비활성 상품이 주어졌을 때__구매하면__원래 500을 보존하고 모든 테이블을 롤백한다`(sku: Long) {
        http(pool()) { mvc, _ ->
            val before = fingerprint()
            val request = body(BUYER, sku, 1)
            val error = send(mvc, "POST", CREATE, token(BUYER), request.value, 500)
            check(
                "C004" == error.path("code").asText() && fingerprint() == before,
                "Original stock/state IllegalState failures must remain atomic server errors",
            )
        }
    }

    @Test
    fun `양수가 아닌 입력이나 금액 넘침이 주어졌을 때__구매하면__400을 돌려주고 재고·검색·이벤트·주문은 그대로다`() {
        http(pool()) { mvc, _ ->
            for (input in listOf(longArrayOf(NORMAL, 0), longArrayOf(0, 1), longArrayOf(73005, 2))) {
                val before = fingerprint()
                val request = body(BUYER, input[0], input[1].toInt())
                val error = send(mvc, "POST", CREATE, token(BUYER), request.value, 400)
                check(
                    "C001" == error.path("code").asText() && fingerprint() == before,
                    "Validation and late multiplyExact overflow must roll back every preceding write",
                )
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["search", "constraint"])
    fun `검색이나 NOT NULL 실패가 주어졌을 때__구매 후 재시도하면__롤백하고 정상으로 복구된다`(failure: String) {
        http(pool()) { mvc, _ ->
            val sku: Long = if (failure == "search") 73006 else NORMAL
            val request = body(BUYER, sku, 1)
            if (failure == "constraint") {
                request.value["receiverName"] = null
            }
            val before = fingerprint()
            val error = send(mvc, "POST", CREATE, token(BUYER), request.value, 500)
            check(
                "C004" == error.path("code").asText() && fingerprint() == before,
                "Search and real NOT NULL errors must remain atomic 500",
            )
            if (failure == "search") {
                jdbc.update("UPDATE pawbridge_store.products SET name='owned recovered search' WHERE id=71005")
            }
            val retry = body(BUYER, sku, 1)
            val created = send(mvc, "POST", CREATE, token(BUYER), retry.value, 201)
            val id = remember(created, retry.owner)
            check(
                sameOrder(created, send(mvc, "GET", GET + id, token(BUYER), null, 200), id),
                "A valid retry must recover through the real service",
            )
            check(
                stock(sku) == (originalSkus.getValue(sku)["stock_quantity"] as Number).toInt() - 1,
                "Only the recovered request may consume stock",
            )
        }
    }

    @Test
    fun `원래 배송 롤백 문자셋 제약이 주어졌을 때__4바이트 문자로 구매하면__400 C007을 돌려주고 모든 쓰기를 롤백한다`() {
        http(pool()) { mvc, _ ->
            val request = body(BUYER, NORMAL, 1)
            request.value["deliveryAddress"] = "synthetic 🐶 address"
            val before = fingerprint()
            val error = send(mvc, "POST", CREATE, token(BUYER), request.value, 400)
            check(
                "C007" == error.path("code").asText() && fingerprint() == before,
                "Only the original Store V3 charset constraint must preserve 400/C007 and atomic rollback",
            )
        }
    }

    @Test
    fun `실제 쓰기·읽기 템플릿이 주어졌을 때__현재 구매자를 확인하면__연결 하나와 올바른 읽기 모드와 쓰기 행 잠금만 쓴다`() {
        http(pool()) { _, context ->
            val pids: MutableList<Int> = ArrayList()
            for (readOnly in listOf(false, true)) {
                val template = context.getBean(
                    if (readOnly) "storeDirectOrderReads" else "storeDirectOrderWrites",
                    TransactionTemplate::class.java,
                )
                template.executeWithoutResult {
                    check(
                        (if (readOnly) "on" else "off") == jdbc.queryForObject("SHOW transaction_read_only", String::class.java) &&
                            "read committed" == jdbc.queryForObject("SHOW transaction_isolation", String::class.java),
                        "The actual Store templates must use the expected PostgreSQL mode and READ COMMITTED",
                    )
                    check(
                        TransactionSynchronizationManager.hasResource(pool()) &&
                            pool().hikariPoolMXBean.activeConnections == 1,
                        "JpaTM and User JDBC must share the sole pooled connection",
                    )
                    val actor = context.getBean(StoreOrderActors::class.java)
                    check(
                        (if (readOnly) actor.currentForRead(BUYER) else actor.currentForWrite(BUYER))!!.active,
                        "Actual current User lookup must join this transaction",
                    )
                    pids.add(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.javaObjectType)!!)
                    try {
                        probe.connection.use { other ->
                            other.prepareStatement(
                                "SELECT user_id FROM pawbridge_user.users WHERE user_id=? FOR UPDATE NOWAIT",
                            ).use { statement ->
                                statement.setLong(1, BUYER)
                                statement.queryTimeout = 3
                                statement.executeQuery().use { rows ->
                                    check(
                                        readOnly && rows.next(),
                                        "Read must not take a User row lock; write must exclude another connection",
                                    )
                                }
                            }
                        }
                    } catch (failure: SQLException) {
                        check(
                            !readOnly && "55P03" == failure.sqlState,
                            "Only write mode may produce the actual NOWAIT lock refusal",
                        )
                    }
                }
                released(pool())
            }
            check(
                pids.first() == pids.last() && pool().maximumPoolSize == 1,
                "RW and RO must reuse the one-slot physical writer pool",
            )
        }
    }

    @Test
    fun `쓰기 연결 둘과 서로 다른 구매자가 주어졌을 때__한 상품을 다투면__구매 하나만 커밋하고 초과 판매는 없다`() {
        http(pool()) { first, _ ->
            newPool("owned-direct-order-second").use { secondPool ->
                http(secondPool) { second, _ ->
                    val one = body(BUYER, CONCURRENT, 1)
                    val two = body(OTHER, CONCURRENT, 1)
                    val executor = Executors.newFixedThreadPool(2)
                    val futures: MutableList<Future<HttpResult>> = ArrayList()
                    try {
                        probe.connection.use { control ->
                            advisory(control, true)
                            val secondStarted = CountDownLatch(1)
                            try {
                                futures.add(executor.submit(Callable { exchange(first, "POST", CREATE, token(BUYER), one.value) }))
                                waitForLockWaiters(control, 1)
                                futures.add(
                                    executor.submit(
                                        Callable {
                                            secondStarted.countDown()
                                            exchange(second, "POST", CREATE, token(OTHER), two.value)
                                        },
                                    ),
                                )
                                check(secondStarted.await(2, TimeUnit.SECONDS), "The second independent request must start")
                                val waiting = waitForLockWaiters(control, 2)
                                check(
                                    waiting.size == 2 && pool().hikariPoolMXBean.activeConnections == 1 &&
                                        secondPool.hikariPoolMXBean.activeConnections == 1,
                                    "Two distinct application PIDs must actually overlap while retaining one connection per pool",
                                )
                            } finally {
                                advisory(control, false)
                            }
                            val statuses: MutableList<Int> = ArrayList()
                            for (i in 0 until futures.size) {
                                val result = futures[i].get(10, TimeUnit.SECONDS)
                                statuses.add(result.status)
                                if (result.status == 201) {
                                    remember(result.body, if (i == 0) one.owner else two.owner)
                                }
                            }
                            statuses.sort()
                            check(
                                statuses == listOf(201, 500) && stock(CONCURRENT) == 0 &&
                                    count(
                                        "SELECT count(*) FROM pawbridge_store.order_items WHERE product_sku_id=?",
                                        CONCURRENT,
                                    ) == 1L &&
                                    events(CONCURRENT).isEmpty(),
                                "The contended source locks must allow one order, no outbox event and no negative stock",
                            )
                        }
                    } finally {
                        for (future in futures) {
                            if (!future.isDone) {
                                future.cancel(true)
                            }
                        }
                        executor.shutdownNow()
                        check(
                            executor.awaitTermination(5, TimeUnit.SECONDS),
                            "Every owned request thread must terminate before cleanup",
                        )
                    }
                    released(pool())
                    released(secondPool)
                }
            }
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
                .withUserConfiguration(StoreDirectOrderConfiguration::class.java, ReadSecurityConfiguration::class.java)
                .withPropertyValues(
                    "pawbridge.store-direct-orders.enabled=true",
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
        config.maximumPoolSize = 1
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
        // Must be unique per run: the fixture database can be reused across runs, so a fixed seed would repeat ids.
        val owner = Ownership(user, sku, "OWNED-P9-" + UUID.randomUUID())
        requests.add(owner)
        val value = LinkedHashMap<String, Any?>()
        value["skuId"] = sku
        value["quantity"] = quantity
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
        return stored.nano % 1000 == 0 && createDifference.compareTo(Duration.ofNanos(1000)) < 0 &&
            loadedAudit == stored && first == second
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

    private class Ownership(val user: Long, val sku: Long, val key: String)

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
                val path = System.getenv("P9_PG_SETTINGS")
                check(path != null && path.isNotBlank(), "P9_PG_SETTINGS is mandatory; owned tests must not skip")
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
                    database.matches(Regex("pb_direct_order_owned_[0-9a-f]{12}")) &&
                        url == "jdbc:postgresql://127.0.0.1:52620/" + database,
                    "Only the exact owned loopback database target is allowed",
                )
                check(
                    username == "pb_direct_order_writer" && marker.matches(Regex("[0-9a-f]{32}")),
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
        private const val CREATE = "/api/v1/orders/direct"
        private const val GET = "/api/v1/orders/"
        private const val BUYER = 910001L
        private const val OTHER = 910002L
        private const val PENDING = 910003L
        private const val FOREIGN_ORDER = 990010L
        private const val NORMAL = 73002L
        private const val CONCURRENT = 73008L
        private const val BARRIER = 9273008L
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
        )
        private val KEY: SecretKey = Keys.hmacShaKeyFor(
            "owned-p9-public-synthetic-signing-key-20261020-only".toByteArray(StandardCharsets.UTF_8),
        )
        private val JSON: ObjectMapper = ObjectMapper().findAndRegisterModules()

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
                .servletPath(path)
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

        private fun fields(value: JsonNode): Set<String> {
            val names = HashSet<String>()
            value.fieldNames().forEachRemaining { names.add(it) }
            return names
        }

        private fun released(source: HikariDataSource) {
            check(
                !TransactionSynchronizationManager.isActualTransactionActive() &&
                    !TransactionSynchronizationManager.hasResource(source),
                "No order transaction or User binding may escape an operation",
            )
            check(
                source.hikariPoolMXBean.activeConnections == 0 && source.hikariPoolMXBean.threadsAwaitingConnection == 0,
                "Every owned one-slot pool must return its connection after success or rollback",
            )
        }

        private fun advisory(control: Connection, acquire: Boolean) {
            control.prepareStatement(
                if (acquire) "SELECT pg_advisory_lock(?)" else "SELECT pg_advisory_unlock(?)",
            ).use { statement ->
                statement.setLong(1, BARRIER)
                statement.queryTimeout = 3
                statement.execute()
            }
        }

        private fun waitForLockWaiters(control: Connection, minimum: Int): Set<Int> {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            do {
                val pids: MutableSet<Int> = LinkedHashSet()
                control.prepareStatement(
                    "SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND state='active' AND wait_event_type='Lock'",
                ).use { statement ->
                    statement.queryTimeout = 2
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            pids.add(rows.getInt(1))
                        }
                    }
                }
                if (pids.size >= minimum) {
                    return pids
                }
                Thread.sleep(25)
            } while (System.nanoTime() < deadline)
            throw AssertionError("Owned independent requests must reach their actual bounded database lock wait")
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
