package com.pawbridge.backend.boot.configuration

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.boot.security.PostTokenAuthenticator
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
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
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration
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
import java.util.Date
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.crypto.SecretKey
import javax.sql.DataSource

/** Candidate secured administrator HTTP/JPA on a fresh P13 database; no payment or original JPA oracle execution. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class OwnedPostgresqlStoreOrderAdministrationTest {
    private lateinit var settings: Settings
    private var pool: HikariDataSource? = null
    private lateinit var probe: DriverManagerDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var baseline: Map<String, Fingerprint>
    private val ownedOrders = LinkedHashMap<Long, Map<String, Any?>>()
    private var unresolvedWorkers = false

    private fun pool(): HikariDataSource = pool!!

    @BeforeAll
    fun verify__the_new_owned_admin_fixture_before_application_connections() {
        observed("owned admin identity") {
            settings = Settings.load()
            probe = DriverManagerDataSource(settings.jdbcUrl, settings.username, settings.password)
            val timeout = Properties()
            timeout.setProperty("connectTimeout", "3")
            timeout.setProperty("socketTimeout", "5")
            probe.setConnectionProperties(timeout)
            verifyServer(JdbcTemplate(probe), settings)
            val source = newPool("owned-admin-order-writer")
            pool = source
            jdbc = JdbcTemplate(source)
            jdbc.queryTimeout = 5
            for (table in TABLE_KEYS.keys) {
                if (table != "pawbridge_user.users" && table != "pawbridge_store.orders") {
                    check(
                        false == jdbc.queryForObject(
                            "SELECT has_table_privilege(current_user,?,'INSERT,UPDATE,DELETE')",
                            Boolean::class.javaObjectType,
                            table,
                        ),
                        "Admin writer must not mutate stock, items, events, Cart or deletion state",
                    )
                }
            }
            check(
                true == jdbc.queryForObject(
                    "SELECT has_table_privilege(current_user,'pawbridge_store.orders','UPDATE') AND NOT has_table_privilege(current_user,'pawbridge_store.orders','INSERT,DELETE') AND NOT has_column_privilege(current_user,'pawbridge_user.users','role','UPDATE')",
                    Boolean::class.javaObjectType,
                ),
                "Only Order UPDATE and the separate User PK lock permission may write",
            )
            for (id in listOf(ORDER_A, ORDER_B)) {
                val row: Map<String, Any?> = jdbc.queryForMap("SELECT * FROM pawbridge_store.orders WHERE id=?", id)
                check(
                    (row["user_id"] as Number).toLong() == USER &&
                        row["delivery_message"] == (if (id == ORDER_A) "OWNED-P13-A" else "OWNED-P13-B"),
                    "Mutable fixture orders must carry their exact pre-owned identity",
                )
                ownedOrders[id] = row
            }
            baseline = fingerprint()
            released(source)
        }
    }

    @BeforeEach
    fun require__the_full_foreign_and_owned_baseline() {
        check(!unresolvedWorkers && fingerprint() == baseline, "Each case requires all fifteen tables at the original baseline")
    }

    @AfterEach
    fun restore__only_the_pre_registered_order_delivery_columns() {
        observed("owned admin order restoration") {
            check(!unresolvedWorkers, "Unsettled workers forbid fixture restoration")
            released(pool())
            for (entry in ownedOrders.entries) {
                val id = entry.key
                val row = entry.value
                check(id == ORDER_A || id == ORDER_B, "No foreign order may enter cleanup")
                val changed = jdbc.update(
                    "UPDATE pawbridge_store.orders SET delivery_status=?,updated_at=? WHERE id=? AND user_id=? AND delivery_message=?",
                    row["delivery_status"], row["updated_at"], id, USER, row["delivery_message"],
                )
                check(changed == 1, "Owned order identity must remain intact before delivery restoration")
            }
            check(
                fingerprint() == baseline,
                "All fifteen tables, including every foreign row and all non-delivery Order columns, must return to baseline",
            )
            released(pool())
        }
    }

    @AfterAll
    fun close__only_the_owned_application_pool() {
        check(!unresolvedWorkers, "Unsettled requests forbid pool close")
        val source = pool
        if (source != null) {
            source.close()
            check(source.isClosed, "Owned admin writer pool must close")
        }
    }

    @Test
    fun givenCurrentAdministratorsAndOtherDbRoles_whenListingReadingAndUpdatingDelivery_thenPreservePageWireAndRejectStaleOrForgedAuthority() {
        http(pool()) { mvc, _ ->
            val first = send(mvc, "GET", ROOT + "?userId=910001&sortBy=id&sortOrder=asc&page=0&size=1", token(ADMIN_A), null, 200)
            check(
                first.path("content").size() == 1 && first.path("content").get(0).path("orderId").asLong() == ORDER_A &&
                    first.path("number").asInt() == 0 && first.path("size").asInt() == 1 &&
                    first.path("totalElements").asLong() == 2L,
                "Admin listing must retain filtered Page metadata and requested scalar order",
            )
            val next = send(mvc, "GET", ROOT + "?userId=910001&sortBy=id&sortOrder=asc&page=1&size=1", token(ADMIN_A), null, 200)
            check(
                next.path("content").size() == 1 && next.path("content").get(0).path("orderId").asLong() == ORDER_B,
                "The next page must contain the second owned purchase",
            )
            val filtered = send(
                mvc, "GET", ROOT + "?userId=910001&status=PAID&deliveryStatus=READY&keyword=ALPHA&sortBy=id&sortOrder=asc",
                token(ADMIN_A), null, 200,
            )
            check(
                filtered.path("content").size() == 1 && filtered.path("totalElements").asLong() == 1L,
                "Status, delivery, member and case-insensitive keyword filters must combine",
            )
            val detail = send(mvc, "GET", ROOT + "/" + ORDER_A, token(ADMIN_A), null, 200)
            check(
                detail.path("userId").asLong() == 910001L && detail.path("items").size() == 1 &&
                    detail.path("items").get(0).path("price").asLong() == 2000L,
                "A current administrator must read another member's original purchase snapshot",
            )
            val before = nonDeliveryFingerprint()
            val changed = send(mvc, "PATCH", delivery(ORDER_A), token(ADMIN_A), mapOf("deliveryStatus" to "SHIPPING"), 200)
            val loaded = send(mvc, "GET", ROOT + "/" + ORDER_A, token(ADMIN_B), null, 200)
            check(
                changed.path("deliveryStatus").asText() == "SHIPPING" && loaded == changed,
                "Delivery update must be committed and visible to another administrator",
            )
            check(
                nonDeliveryFingerprint() == before,
                "Payment status, amounts, items, stock, events, Cart and User fields must remain unchanged",
            )
            for (user in listOf(USER, SHELTER, PENDING, MISSING)) {
                send(mvc, "GET", ROOT, token(user), null, 403)
                send(mvc, "GET", ROOT + "/" + ORDER_A, token(user), null, 403)
                send(mvc, "PATCH", delivery(ORDER_A), token(user), mapOf("deliveryStatus" to "DELIVERED"), 403)
            }
            send(mvc, "GET", ROOT, tokenWithRole(ADMIN_A, "ROLE_USER"), null, 403)
            for (path in listOf(ROOT, ROOT + "/" + ORDER_A)) {
                send(mvc, "GET", path, null, null, 401)
            }
            send(mvc, "PATCH", delivery(ORDER_A), null, mapOf("deliveryStatus" to "DELIVERED"), 401)
            send(mvc, "POST", ROOT, token(ADMIN_A), mapOf(), 403)
            send(mvc, "DELETE", ROOT + "/" + ORDER_A, token(ADMIN_A), null, 403)
            send(mvc, "GET", delivery(ORDER_A), token(ADMIN_A), null, 403)
            check(
                nonDeliveryFingerprint() == before && deliveryValue(ORDER_A) == "SHIPPING",
                "Denied requests must not change any protected row or the committed delivery state",
            )
        }
    }

    @Test
    fun givenTwoPopulatedOrdersAndAnEmptyPage_whenListingAtMaximumPageSize_thenBatchItemsWithoutChangingPageOrderOrIssuingPerOrderQueries() {
        http(pool()) { mvc, context ->
            val factory = context.getBean("storeDirectOrderEntityManagerFactory", EntityManagerFactory::class.java)
            val statistics = factory.unwrap(SessionFactory::class.java).statistics
            val previous = statistics.isStatisticsEnabled
            statistics.isStatisticsEnabled = true
            try {
                statistics.clear()
                val page = send(
                    mvc, "GET", ROOT + "?userId=910001&sortBy=id&sortOrder=desc&page=0&size=100", token(ADMIN_A), null, 200,
                )
                check(
                    page.path("size").asInt() == 100 && page.path("totalElements").asLong() == 2L &&
                        page.path("content").size() == 2,
                    "Batch loading must preserve the maximum page size, filtered total and both populated orders",
                )
                check(
                    page.path("content").get(0).path("orderId").asLong() == ORDER_B &&
                        page.path("content").get(1).path("orderId").asLong() == ORDER_A &&
                        page.path("content").get(0).path("items").size() == 1 &&
                        page.path("content").get(1).path("items").size() == 1,
                    "Collection fetching must preserve the original descending Page order and each purchase snapshot",
                )
                check(
                    statistics.prepareStatementCount == 2L,
                    "An inferred-count two-order page must issue only its page query and one collection fetch, never a query per order",
                )
                statistics.clear()
                val empty = send(mvc, "GET", ROOT + "?userId=999998&sortBy=id&size=100", token(ADMIN_A), null, 200)
                check(
                    empty.path("content").isEmpty && empty.path("totalElements").asLong() == 0L &&
                        statistics.prepareStatementCount == 1L,
                    "An empty first page must not issue a collection query or add an unnecessary count query",
                )
                check(fingerprint() == baseline, "Batched listing must leave every fixture table unchanged")
            } finally {
                statistics.isStatisticsEnabled = previous
            }
        }
    }

    @Test
    fun givenNullDeliveryOrMissingOrders_whenUpdatingOrReading_thenReturnSafe500AndOriginalMissingBoundariesWithFullRollbackAndNoPrivateLogs() {
        http(pool()) { mvc, _ ->
            val before = fingerprint()
            val body = LinkedHashMap<String, Any?>()
            body["deliveryStatus"] = null
            val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val events = ListAppender<ILoggingEvent>()
            events.start()
            logger.addAppender(events)
            try {
                send(mvc, "PATCH", delivery(ORDER_A), token(ADMIN_A), body, 500)
                for (event in events.list) {
                    check(
                        event.loggerName != "org.hibernate.engine.jdbc.spi.SqlExceptionHelper" && event.throwableProxy == null,
                        "Rejected null delivery must not emit Hibernate SQL failure or exception details",
                    )
                    val message = event.formattedMessage
                    check(
                        !message.contains("Failing row") && !message.contains("SqlExceptionHelper"),
                        "Rejected null delivery must not log a database failed-row diagnostic",
                    )
                    for (column in listOf("delivery_address", "receiver_name", "receiver_phone", "delivery_message")) {
                        check(
                            !message.contains(ownedOrders[ORDER_A]!![column].toString()),
                            "Rejected null delivery must not log synthetic recipient, address or memo values",
                        )
                    }
                }
            } finally {
                logger.detachAppender(events)
                events.stop()
            }
            check(
                fingerprint() == before,
                "Null delivery must return a safe 500 and roll back the writer transaction without changing any fixture table",
            )
            released(pool())
            send(mvc, "GET", ROOT + "/999999", token(ADMIN_A), null, 404)
            send(mvc, "PATCH", delivery(999999), token(ADMIN_A), mapOf("deliveryStatus" to "DELIVERED"), 400)
            check(fingerprint() == before, "Missing detail and update must preserve all fifteen tables")
        }
    }

    @Test
    fun givenTwoDifferentCurrentAdminsAndAnOrderHolder_whenBothDeliveryUpdatesWait_thenSerializeOnTheOrderAndPreserveEveryOtherField() {
        http(pool()) { mvc, _ ->
            val before = nonDeliveryFingerprint()
            val workers: ExecutorService = Executors.newFixedThreadPool(2)
            unresolvedWorkers = true
            try {
                lateinit var a: Future<HttpResult>
                lateinit var b: Future<HttpResult>
                probe.connection.use { control ->
                    control.autoCommit = false
                    control.prepareStatement("SELECT id FROM pawbridge_store.orders WHERE id=? FOR UPDATE").use { statement ->
                        statement.setLong(1, ORDER_A)
                        statement.queryTimeout = 3
                        statement.execute()
                    }
                    var holder = 0
                    control.prepareStatement("SELECT pg_backend_pid()").use { statement ->
                        statement.executeQuery().use { rows ->
                            rows.next()
                            holder = rows.getInt(1)
                        }
                    }
                    a = workers.submit(
                        Callable { exchange(mvc, "PATCH", delivery(ORDER_A), token(ADMIN_A), mapOf("deliveryStatus" to "SHIPPING")) },
                    )
                    b = workers.submit(
                        Callable { exchange(mvc, "PATCH", delivery(ORDER_A), token(ADMIN_B), mapOf("deliveryStatus" to "DELIVERED")) },
                    )
                    val waiting = waitForOrderLocks(control, holder)
                    check(
                        waiting.size == 2,
                        "Different administrators must occupy two physical PIDs blocked on the same actual Order SELECT lock",
                    )
                    control.commit()
                }
                val ar = a.get(6, TimeUnit.SECONDS)
                val br = b.get(6, TimeUnit.SECONDS)
                check(
                    ar.status == 200 && br.status == 200 && ar.body.path("deliveryStatus").asText() == "SHIPPING" &&
                        br.body.path("deliveryStatus").asText() == "DELIVERED",
                    "Both serialized updates must return their own committed delivery snapshot",
                )
                check(
                    setOf("SHIPPING", "DELIVERED").contains(deliveryValue(ORDER_A)) && nonDeliveryFingerprint() == before,
                    "Only delivery and its audit timestamp may change; no deterministic winner is invented",
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
                    throw AssertionError("Interrupted workers remain fenced from cleanup")
                } finally {
                    unresolvedWorkers = !settled
                }
                check(settled, "Workers must terminate before owned order restoration or pool close")
            }
            released(pool())
        }
    }

    private fun deliveryValue(id: Long): String {
        return jdbc.queryForObject("SELECT delivery_status FROM pawbridge_store.orders WHERE id=?", String::class.java, id)!!
    }

    private fun http(source: HikariDataSource, action: HttpCheck) {
        observed("actual secured order MVC/JPA") {
            val factory = AtomicReference<EntityManagerFactory>()
            WebApplicationContextRunner().withConfiguration(
                AutoConfigurations.of(
                    PropertyPlaceholderAutoConfiguration::class.java,
                    JacksonAutoConfiguration::class.java,
                    HttpMessageConvertersAutoConfiguration::class.java,
                    ValidationAutoConfiguration::class.java,
                    DispatcherServletAutoConfiguration::class.java,
                    WebMvcAutoConfiguration::class.java,
                    SpringDataWebAutoConfiguration::class.java,
                    SecurityAutoConfiguration::class.java,
                    SecurityFilterAutoConfiguration::class.java,
                ),
            )
                .withUserConfiguration(StoreOrderAdministrationConfiguration::class.java, ReadSecurityConfiguration::class.java)
                .withPropertyValues(
                    "pawbridge.store-order-administration.enabled=true",
                    "pawbridge.store-carts.enabled=false",
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
            check(factory.get() != null && !factory.get().isOpen, "The owned context must close its Store EntityManagerFactory")
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

    private fun fingerprint(): Map<String, Fingerprint> = fingerprint(false)

    private fun nonDeliveryFingerprint(): Map<String, Fingerprint> = fingerprint(true)

    private fun fingerprint(ignoreOwnedDelivery: Boolean): Map<String, Fingerprint> {
        val state = LinkedHashMap<String, Fingerprint>()
        TABLE_KEYS.forEach { (table, key) ->
            val value = if (ignoreOwnedDelivery && table == "pawbridge_store.orders") {
                "CASE WHEN id=910010 THEN to_jsonb(t)-'delivery_status'-'updated_at' ELSE to_jsonb(t) END"
            } else {
                "row_to_json(t)"
            }
            val row = jdbc.queryForMap(
                "SELECT count(*) AS n,md5(COALESCE(string_agg((" + value + ")::text,',' ORDER BY " + key + "),'')) AS digest FROM " + table + " t",
            )
            state[table] = Fingerprint((row["n"] as Number).toLong(), row["digest"] as String)
        }
        return state
    }

    private fun interface Checked {
        fun run()
    }

    private fun interface HttpCheck {
        fun run(mvc: MockMvc, context: ApplicationContext)
    }

    private class HttpResult(val status: Int, val body: JsonNode)

    private class Fingerprint(val count: Long, val digest: String) {
        override fun equals(other: Any?): Boolean {
            return other is Fingerprint && count == other.count && digest == other.digest
        }

        override fun hashCode(): Int = 31 * count.hashCode() + digest.hashCode()
    }

    private class Settings(
        val jdbcUrl: String,
        val database: String,
        val username: String,
        val password: String,
        val marker: String,
    ) {
        override fun toString(): String = "OwnedAdminOrderSettings[redacted]"

        companion object {
            fun load(): Settings {
                val path = System.getenv("P13_PG_SETTINGS")
                check(path != null && path.isNotBlank(), "P13_PG_SETTINGS is mandatory; owned tests must not skip")
                val file = Path.of(path)
                check(
                    file.isAbsolute && file.normalize() == file && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(file),
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
                    !permissions.contains(PosixFilePermission.GROUP_WRITE) && !permissions.contains(PosixFilePermission.OTHERS_WRITE),
                    "Settings parent must reject other-user writes",
                )
                val values = Properties()
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input: InputStream -> values.load(input) }
                val database = required(values, "database")
                val url = required(values, "jdbcUrl")
                val username = required(values, "username")
                val marker = required(values, "marker")
                check(
                    database.matches(Regex("pb_admin_order_owned_[0-9a-f]{12}")) &&
                        url == "jdbc:postgresql://127.0.0.1:52620/" + database,
                    "Only the exact owned loopback database target is allowed",
                )
                check(username == "pb_admin_order_writer" && marker.matches(Regex("[0-9a-f]{32}")), "Writer and marker must be explicit")
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
        private const val ROOT = "/api/v1/admin/orders"
        private const val ADMIN_A = 910101L
        private const val ADMIN_B = 910102L
        private const val USER = 910001L
        private const val SHELTER = 910103L
        private const val PENDING = 910104L
        private const val MISSING = 910199L
        private const val ORDER_A = 910010L
        private const val ORDER_B = 910011L
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
        private val KEY: SecretKey =
            Keys.hmacShaKeyFor("owned-p13-public-synthetic-signing-key-20261021-only".toByteArray(StandardCharsets.UTF_8))
        private val JSON: ObjectMapper = ObjectMapper().findAndRegisterModules()

        private fun delivery(id: Long): String = ROOT + "/" + id + "/delivery-status"

        private fun waitForOrderLocks(control: Connection, holder: Int): Set<Int> {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            do {
                control.prepareStatement("SELECT pg_stat_clear_snapshot()").use { clear -> clear.execute() }
                val found = LinkedHashSet<Int>()
                control.prepareStatement(
                    "SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND pid<>pg_backend_pid() AND state='active' AND wait_event_type='Lock' AND (?=ANY(pg_blocking_pids(pid)) OR EXISTS(SELECT 1 FROM unnest(pg_blocking_pids(pid)) b WHERE ?=ANY(pg_blocking_pids(b)))) AND query ILIKE '%pawbridge_store.orders%' AND (query ILIKE '%for update%' OR query ILIKE '%for no key update%')",
                ).use { statement ->
                    statement.setInt(1, holder)
                    statement.setInt(2, holder)
                    statement.queryTimeout = 2
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            found.add(rows.getInt(1))
                        }
                    }
                }
                if (found.size == 2) {
                    return found
                }
                Thread.sleep(20)
            } while (System.nanoTime() < until)
            throw AssertionError("Both current administrators must reach the controlled Order lock")
        }

        private fun token(user: Long): String = tokenWithRole(user, "ROLE_ADMIN")

        private fun tokenWithRole(user: Long, role: String): String {
            return "Bearer " + Jwts.builder()
                .claim("userId", user)
                .claim("role", role)
                .expiration(Date(System.currentTimeMillis() + 300_000))
                .signWith(KEY)
                .compact()
        }

        private fun send(mvc: MockMvc, method: String, path: String, authorization: String?, body: Map<String, Any?>?, expected: Int): JsonNode {
            val response = exchange(mvc, method, path, authorization, body)
            check(response.status == expected, "Unexpected administrator HTTP status: expected=" + expected + ",actual=" + response.status)
            return response.body
        }

        private fun exchange(mvc: MockMvc, method: String, path: String, authorization: String?, body: Map<String, Any?>?): HttpResult {
            val request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path)
                .servletPath(path.split("?", limit = 2)[0])
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .header("X-User-Id", ADMIN_A.toString())
                .header("X-User-Role", "ROLE_ADMIN")
            if (authorization != null) {
                request.header("Authorization", authorization)
            }
            if (body != null) {
                request.content(JSON.writeValueAsString(body))
            }
            val response = mvc.perform(request).andReturn().response
            check(response.getHeader("Set-Cookie") == null, "Administrator routes must remain stateless")
            val content = response.getContentAsString(StandardCharsets.UTF_8)
            return HttpResult(response.status, if (content.isEmpty()) JSON.nodeFactory.nullNode() else JSON.readTree(content))
        }

        private fun released(source: HikariDataSource) {
            check(
                !TransactionSynchronizationManager.isActualTransactionActive() && !TransactionSynchronizationManager.hasResource(source),
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
            check(markers.size == 1 && settings.marker == markers.first(), "Only the exact isolated fixture marker is accepted")
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
                throw AssertionError("Owned direct order observation failed: " + phase + "; type=" + failure.javaClass.name)
            }
        }

        private fun check(condition: Boolean, fixedMessage: String) {
            if (!condition) {
                throw AssertionError(fixedMessage)
            }
        }
    }
}
