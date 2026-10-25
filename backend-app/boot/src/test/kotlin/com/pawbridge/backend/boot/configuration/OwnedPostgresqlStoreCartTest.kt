package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.boot.security.PostTokenAuthenticator
import com.pawbridge.backend.store.internal.memberread.infrastructure.JdbcStoreMemberReads
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
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

/** Actual secured Cart HTTP/JPA against a fresh, owned and minimally writable fixture. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class OwnedPostgresqlStoreCartTest {
    private lateinit var settings: Settings
    private var pool: HikariDataSource? = null
    private lateinit var jdbc: JdbcTemplate
    private lateinit var probe: DriverManagerDataSource
    private lateinit var baseline: Map<String, String>
    private var unresolvedWorkers = false

    private fun pool(): HikariDataSource = pool!!

    @BeforeAll
    fun given__owned_cart_settings__when__opening_fixture__then__verify_target_marker_and_minimum_permissions() {
        observed("owned Cart identity") {
            settings = Settings.load()
            probe = DriverManagerDataSource(settings.jdbcUrl, settings.username, settings.password)
            val timeouts = Properties()
            timeouts.setProperty("connectTimeout", "3")
            timeouts.setProperty("socketTimeout", "5")
            probe.setConnectionProperties(timeouts)
            verifyServer(JdbcTemplate(probe), settings)
            val config = HikariConfig()
            config.jdbcUrl = settings.jdbcUrl
            config.username = settings.username
            config.password = settings.password
            config.poolName = "owned-cart-writer"
            config.maximumPoolSize = 2
            config.minimumIdle = 0
            config.connectionTimeout = 2000
            config.validationTimeout = 1000
            config.initializationFailTimeout = -1
            config.addDataSourceProperty("connectTimeout", "3")
            config.addDataSourceProperty("socketTimeout", "10")
            val source = HikariDataSource(config)
            pool = source
            jdbc = JdbcTemplate(source)
            jdbc.queryTimeout = 5
            check(
                count("SELECT count(*) FROM pawbridge_store.carts WHERE user_id IN (910001,910002,910003,910099)") == 0L,
                "Owned Cart users must start without any earlier trial rows",
            )
            for (table in listOf(
                "pawbridge_store.orders",
                "pawbridge_store.order_items",
                "pawbridge_store.outbox",
                "pawbridge_store.product_skus",
                "pawbridge_store.products",
                "pawbridge_store.product_search_documents",
            )) {
                check(
                    false == jdbc.queryForObject(
                        "SELECT has_table_privilege(current_user,?,'INSERT,UPDATE,DELETE')",
                        Boolean::class.javaObjectType,
                        table,
                    ),
                    "Cart writer must not mutate stock, orders or event/search tables",
                )
            }
            baseline = fingerprint()
            released()
        }
    }

    @BeforeEach
    fun given__foreign_baseline__when__starting_case__then__require_no_owned_rows_or_live_workers() {
        check(!unresolvedWorkers && fingerprint() == baseline, "Cases must preserve all fifteen fixture tables")
    }

    @AfterEach
    fun given__pre_registered_cart_users__when__case_finishes__then__clean_only_their_carts_and_preserve_every_foreign_row() {
        check(!unresolvedWorkers, "Unfinished requests forbid row cleanup or pool shutdown")
        released()
        jdbc.update(
            "DELETE FROM pawbridge_store.cart_items WHERE cart_id IN (SELECT id FROM pawbridge_store.carts WHERE user_id IN (?,?,?,?))",
            BUYER, OTHER, PENDING, 910099L,
        )
        jdbc.update("DELETE FROM pawbridge_store.carts WHERE user_id IN (?,?,?,?)", BUYER, OTHER, PENDING, 910099L)
        check(
            fingerprint() == baseline,
            "Only pre-dispatch owned Cart rows may have changed; foreign tables and rows must be identical",
        )
        released()
    }

    @AfterAll
    fun given__settled_requests__when__fixture_owner_closes__then__close_only_owned_pool() {
        check(!unresolvedWorkers, "Unfinished request evidence must remain available")
        val source = pool
        if (source != null) {
            source.close()
            check(source.isClosed, "Owned Cart pool must close")
        }
    }

    @Test
    fun given__verified_buyer_and_forged_headers__when__managing_cart__then__preserve_original_wire_and_immediately_persist_only_own_rows() {
        http { mvc, context ->
            val empty = send(mvc, "GET", ROOT, BUYER, null, 200)
            check(empty.isArray && empty.isEmpty, "New Cart GET must be an empty array")
            send(mvc, "POST", ROOT + "/items", BUYER, mapOf("skuId" to SKU, "quantity" to 2, "userId" to FOREIGN), 200)
            val first = send(mvc, "GET", ROOT, BUYER, null, 200)
            check(first.isArray && first.size() == 1, "Cart GET must use the original array shape")
            val item = first.get(0)
            val names = HashSet<String>()
            item.fieldNames().forEachRemaining { name -> names.add(name) }
            check(
                names == setOf("id", "skuId", "skuCode", "productName", "price", "quantity", "totalPrice") &&
                    item.path("id").isNull && item.path("skuId").asLong() == SKU && item.path("quantity").asInt() == 2 &&
                    item.path("price").asLong() == 2000L && item.path("totalPrice").asLong() == 4000L,
                "Original null item ID, price and quantity fields must survive DB-backed persistence",
            )
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pawbridge_store.carts c JOIN pawbridge_store.cart_items i ON i.cart_id=c.id WHERE c.user_id=? AND i.product_sku_id=? AND i.sku_id=? AND i.quantity=2 AND c.created_at IS NOT NULL AND i.created_at IS NOT NULL",
                    Long::class.javaObjectType,
                    BUYER,
                    SKU,
                    SKU,
                ) == 1L,
                "Both existing SKU columns and JPA audit fields must be committed before HTTP success",
            )
            val memberReads = JdbcStoreMemberReads(
                NamedParameterJdbcTemplate(jdbc),
                context.getBean("storeDirectOrderReads", TransactionTemplate::class.java),
            )
            val persisted = memberReads.cart(BUYER).orElseThrow()
            check(
                persisted.userId == BUYER && persisted.items!!.size == 1 && persisted.items!!.first().productSkuId == SKU &&
                    persisted.items!!.first().quantity == 2 && memberReads.cart(OTHER).isEmpty,
                "Existing User MyPage Cart reads must see committed SKU data while an uncreated Cart remains absent",
            )
            send(mvc, "POST", ROOT + "/items", BUYER, mapOf("skuId" to SKU, "quantity" to 3), 200)
            check(send(mvc, "GET", ROOT, BUYER, null, 200).get(0).path("quantity").asInt() == 5, "Add must accumulate existing SKU quantity")
            send(mvc, "PATCH", ROOT + "/items/" + SKU + "?quantity=4", BUYER, null, 200)
            check(
                send(mvc, "GET", ROOT, BUYER, null, 200).get(0).path("quantity").asInt() == 4,
                "PATCH query quantity must set rather than accumulate",
            )
            check(send(mvc, "GET", ROOT, OTHER, null, 200).isEmpty, "Another authenticated member must not see the buyer Cart")
            check(memberReads.cart(OTHER).isEmpty, "GET on a never-created Cart must not create an empty parent row")
            send(mvc, "PATCH", ROOT + "/items/" + SKU + "?quantity=0", BUYER, null, 200)
            check(send(mvc, "GET", ROOT, BUYER, null, 200).isEmpty, "Nonpositive PATCH quantity must remove the item")
            send(mvc, "POST", ROOT + "/items", BUYER, mapOf("skuId" to SKU, "quantity" to 1), 200)
            send(mvc, "DELETE", ROOT + "/items/" + SKU, BUYER, null, 200)
            check(send(mvc, "GET", ROOT, BUYER, null, 200).isEmpty, "DELETE item must persist removal")
            send(mvc, "POST", ROOT + "/items", BUYER, mapOf("skuId" to SKU, "quantity" to 1), 200)
            send(mvc, "DELETE", ROOT, BUYER, null, 200)
            check(send(mvc, "GET", ROOT, BUYER, null, 200).isEmpty, "DELETE Cart must persist clearing")
            check(
                memberReads.cart(BUYER).isPresent && memberReads.cart(BUYER).orElseThrow().items!!.isEmpty(),
                "Clearing items must preserve the original persisted-empty Cart versus never-created distinction",
            )
            check(
                count("SELECT quantity FROM pawbridge_store.cart_items WHERE id=990041") == 3L,
                "Forged identity must never mutate the protected foreign Cart",
            )
        }
    }

    @Test
    fun given__invalid_or_unavailable_members_and_overflow__when__calling_cart__then__reject_without_partial_cart_or_cross_domain_writes() {
        http { mvc, _ ->
            send(mvc, "GET", ROOT, null, null, 401)
            for (user in listOf(PENDING, 910099L)) {
                send(mvc, "GET", ROOT, user, null, 403)
                send(mvc, "POST", ROOT + "/items", user, mapOf("skuId" to SKU, "quantity" to 1), 403)
            }
            val invalidBodies = listOf<Map<String, Any>>(
                mapOf("skuId" to 999999, "quantity" to 1),
                mapOf("skuId" to SKU, "quantity" to 0),
                mapOf("skuId" to 73005, "quantity" to 2),
            )
            for (body in invalidBodies) {
                send(mvc, "POST", ROOT + "/items", BUYER, body, 400)
                check(fingerprint() == baseline, "Invalid SKU, quantity and price overflow must roll back parent and item creation")
            }
            send(mvc, "POST", ROOT + "/items", BUYER, mapOf("skuId" to SKU, "quantity" to 1), 200)
            send(mvc, "PATCH", ROOT + "/items/" + SKU + "?quantity=" + Int.MAX_VALUE, BUYER, null, 200)
            val before = fingerprint()
            send(mvc, "POST", ROOT + "/items", BUYER, mapOf("skuId" to SKU, "quantity" to 1), 400)
            check(fingerprint() == before, "Quantity accumulation overflow must preserve the previous committed quantity")
            send(mvc, "GET", ROOT + "/reset", BUYER, null, 403)
        }
    }

    @Test
    fun given__two_real_sessions_waiting_on_one_member_lock__when__adding_same_sku__then__commit_one_cart_one_item_and_both_quantities() {
        http { mvc, _ ->
            val workers: ExecutorService = Executors.newFixedThreadPool(2)
            val futures = ArrayList<Future<Int>>()
            unresolvedWorkers = true
            try {
                probe.connection.use { control ->
                    control.autoCommit = false
                    control.prepareStatement("SELECT user_id FROM pawbridge_user.users WHERE user_id=? FOR UPDATE").use { statement ->
                        statement.setLong(1, BUYER)
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
                    for (quantity in listOf(2, 3)) {
                        futures.add(
                            workers.submit(
                                Callable {
                                    exchange(mvc, "POST", ROOT + "/items", BUYER, mapOf("skuId" to SKU, "quantity" to quantity)).status
                                },
                            ),
                        )
                    }
                    val waiting = HashSet<Int>()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (System.nanoTime() < deadline) {
                        control.prepareStatement("SELECT pg_stat_clear_snapshot()").use { clear -> clear.execute() }
                        waiting.clear()
                        control.prepareStatement(
                            "SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND pid<>pg_backend_pid() AND state='active' AND wait_event_type='Lock' AND ( ? = ANY(pg_blocking_pids(pid)) OR EXISTS (SELECT 1 FROM unnest(pg_blocking_pids(pid)) b WHERE ? = ANY(pg_blocking_pids(b))))",
                        ).use { statement ->
                            statement.setInt(1, holder)
                            statement.setInt(2, holder)
                            statement.queryTimeout = 2
                            statement.executeQuery().use { rows ->
                                while (rows.next()) {
                                    waiting.add(rows.getInt(1))
                                }
                            }
                        }
                        if (waiting.size == 2) {
                            break
                        }
                        Thread.sleep(20)
                    }
                    check(waiting.size == 2, "Both independent application PIDs must reach the actual member lock/blocker chain")
                    control.commit()
                    for (future in futures) {
                        check(future.get(5, TimeUnit.SECONDS) == 200, "Both locked Cart mutations must succeed")
                    }
                }
            } finally {
                for (future in futures) {
                    if (!future.isDone) {
                        future.cancel(true)
                    }
                }
                workers.shutdownNow()
                val settled = workers.awaitTermination(5, TimeUnit.SECONDS)
                unresolvedWorkers = !settled
                check(settled, "Workers must terminate before any fixture cleanup or pool close")
            }
            check(
                count("SELECT count(*) FROM pawbridge_store.carts WHERE user_id=910001") == 1L &&
                    count("SELECT count(*) FROM pawbridge_store.cart_items i JOIN pawbridge_store.carts c ON c.id=i.cart_id WHERE c.user_id=910001") == 1L &&
                    count("SELECT i.quantity FROM pawbridge_store.cart_items i JOIN pawbridge_store.carts c ON c.id=i.cart_id WHERE c.user_id=910001") == 5L,
                "Row locking must preserve both additions without duplicate parent/item or lost update",
            )
            released()
        }
    }

    private fun http(action: HttpCheck) {
        observed("secured Cart HTTP/JPA") {
            val source = pool()
            val factory = AtomicReference<EntityManagerFactory>()
            WebApplicationContextRunner().withConfiguration(
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
                .withPropertyValues("pawbridge.store-carts.enabled=true", "spring.sql.init.mode=never", "spring.jmx.enabled=false")
                .withBean(
                    "writeDataSource",
                    DataSource::class.java,
                    Supplier { source },
                    BeanDefinitionCustomizer { definition -> definition.setDestroyMethodName("") },
                )
                .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(source) })
                .withBean(PostTokenAuthenticator::class.java, Supplier { PostTokenAuthenticator(KEY) })
                .run { context ->
                    check(context.startupFailure == null, "Only the real Cart and security configurations must start")
                    factory.set(context.getBean("storeDirectOrderEntityManagerFactory", EntityManagerFactory::class.java))
                    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context)
                        .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
                        .build()
                    try {
                        action.run(mvc, context)
                    } catch (failure: AssertionError) {
                        throw failure
                    } catch (failure: Exception) {
                        throw AssertionError("Owned Cart HTTP failed: " + failure.javaClass.name)
                    }
                }
            check(factory.get() != null && !factory.get().isOpen, "Context close must release its single Store EntityManagerFactory")
            check(!source.isClosed, "Context must preserve the externally owned pool")
            released()
        }
    }

    private fun count(sql: String): Long = jdbc.queryForObject(sql, Long::class.javaObjectType)!!

    private fun fingerprint(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        TABLE_KEYS.forEach { (table, key) ->
            result[table] = jdbc.queryForObject(
                "SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY " + key + "),'')) FROM " + table + " t",
                String::class.java,
            )!!
        }
        return result
    }

    private fun released() {
        val source = pool()
        check(
            !TransactionSynchronizationManager.isActualTransactionActive() && !TransactionSynchronizationManager.hasResource(source),
            "No transaction or connection binding may escape a Cart operation",
        )
        check(
            source.hikariPoolMXBean.activeConnections == 0 && source.hikariPoolMXBean.threadsAwaitingConnection == 0,
            "All owned Cart connections must return after commit or rollback",
        )
    }

    private fun interface Checked {
        fun run()
    }

    private fun interface HttpCheck {
        fun run(mvc: MockMvc, context: ApplicationContext)
    }

    private class Result(val status: Int, val body: JsonNode, val emptyBody: Boolean)

    private class Settings(
        val jdbcUrl: String,
        val database: String,
        val username: String,
        val password: String,
        val marker: String,
    ) {
        override fun toString(): String = "OwnedCartSettings[redacted]"

        companion object {
            fun load(): Settings {
                val path = System.getenv("P11_PG_SETTINGS")
                check(path != null && path.isNotBlank(), "P11_PG_SETTINGS is mandatory; owned tests must not skip")
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
                    database.matches(Regex("pb_cart_owned_[0-9a-f]{12}")) &&
                        url == "jdbc:postgresql://127.0.0.1:52620/" + database,
                    "Only the exact owned loopback database target is allowed",
                )
                check(username == "pb_cart_writer" && marker.matches(Regex("[0-9a-f]{32}")), "Writer and marker must be explicit")
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
        private const val BUYER = 910001L
        private const val OTHER = 910002L
        private const val PENDING = 910003L
        private const val SKU = 73002L
        private const val FOREIGN = 990001L
        private const val ROOT = "/api/v1/carts"
        private val JSON: ObjectMapper = ObjectMapper().findAndRegisterModules()
        private val KEY: SecretKey =
            Keys.hmacShaKeyFor("owned-cart-public-synthetic-signing-key-20261021-only".toByteArray(StandardCharsets.UTF_8))
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

        private fun token(user: Long): String {
            return "Bearer " + Jwts.builder()
                .claim("userId", user)
                .claim("role", "ROLE_USER")
                .expiration(Date(System.currentTimeMillis() + 300_000))
                .signWith(KEY)
                .compact()
        }

        private fun send(mvc: MockMvc, method: String, path: String, user: Long?, body: Map<String, Any>?, status: Int): JsonNode {
            val result = exchange(mvc, method, path, user, body)
            check(result.status == status, "Unexpected Cart HTTP status: expected=" + status + ",actual=" + result.status)
            if (status == 200 && method != "GET") {
                check(result.emptyBody, "Original Cart mutation success must have an empty body")
            }
            return result.body
        }

        private fun exchange(mvc: MockMvc, method: String, url: String, user: Long?, body: Map<String, Any>?): Result {
            val servlet = url.split("?", limit = 2)[0]
            val request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), url)
                .servletPath(servlet)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", FOREIGN.toString())
                .header("X-User-Role", "ROLE_ADMIN")
            if (user != null) {
                request.header("Authorization", token(user))
            }
            if (body != null) {
                request.content(JSON.writeValueAsString(body))
            }
            val response = mvc.perform(request).andReturn().response
            check(response.getHeader("Set-Cookie") == null, "Cart API must remain stateless")
            val content = response.getContentAsString(StandardCharsets.UTF_8)
            return Result(
                response.status,
                if (content.isEmpty()) JSON.nodeFactory.nullNode() else JSON.readTree(content),
                content.isEmpty(),
            )
        }

        private fun observed(phase: String, action: Checked) {
            try {
                action.run()
            } catch (failure: AssertionError) {
                throw failure
            } catch (failure: Throwable) {
                throw AssertionError("Owned Cart observation failed: " + phase + ";type=" + failure.javaClass.name)
            }
        }

        private fun check(value: Boolean, message: String) {
            if (!value) {
                throw AssertionError(message)
            }
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
    }
}
