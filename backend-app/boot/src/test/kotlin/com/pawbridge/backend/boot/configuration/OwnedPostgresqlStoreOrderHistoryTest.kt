package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.InputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.Calendar
import java.util.HexFormat
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import java.util.TreeMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(60)
class OwnedPostgresqlStoreOrderHistoryTest {
    private val levels = LinkedHashMap<ch.qos.logback.classic.Logger, ch.qos.logback.classic.Level?>()
    private lateinit var settings: Settings
    private var pool: ObservedPool? = null
    private lateinit var jdbc: JdbcTemplate
    private var context: AnnotationConfigApplicationContext? = null
    private lateinit var orders: DirectOrders
    private lateinit var baseline: Map<String, String>
    private var nextAllowed = false
    private var completedBody = false
    private var nextOrder = 1

    private fun pool(): ObservedPool = pool!!

    @BeforeAll
    fun givenExactFreshP21__whenActualConfigurationStarts__thenUseOnlySelectRoleAndExistingActor() {
        observed("P21 setup") {
            quiet()
            settings = Settings.load()
            javaClass.getResourceAsStream("/owned-postgresql/store-order-history-owned-schema.sql").use { resource ->
                check(resource != null && sha(resource.readAllBytes()) == SQL_SHA, "Frozen exact P21 schema is required")
            }
            val config = HikariConfig()
            config.jdbcUrl = settings.url
            config.username = settings.user
            config.password = settings.password
            config.poolName = "owned-p21-store-history"
            config.maximumPoolSize = 2
            config.minimumIdle = 0
            config.initializationFailTimeout = -1
            config.connectionTimeout = 2000
            config.validationTimeout = 1000
            config.addDataSourceProperty("connectTimeout", "3")
            config.addDataSourceProperty("socketTimeout", "5")
            val observedPool = ObservedPool(config)
            pool = observedPool
            jdbc = JdbcTemplate(observedPool)
            jdbc.queryTimeout = 5
            verifyTarget()
            baseline = fingerprints()
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pawbridge_store.orders WHERE user_id=910001",
                    Long::class.javaObjectType,
                ) == 7L,
                "Exact owner seed, not an earlier trial, is required",
            )
            val application = AnnotationConfigApplicationContext()
            context = application
            TestPropertyValues.of("pawbridge.store-direct-orders.enabled=true", "pawbridge.store-carts.enabled=false")
                .applyTo(application)
            application.registerBean(
                "writeDataSource",
                HikariDataSource::class.java,
                Supplier { observedPool },
                BeanDefinitionCustomizer { definition -> definition.setDestroyMethodName("") },
            )
            application.registerBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            application.registerBean(ObjectMapper::class.java, Supplier { ObjectMapper().findAndRegisterModules() })
            application.register(PropertyPlaceholderAutoConfiguration::class.java, StoreDirectOrderConfiguration::class.java)
            application.refresh()
            orders = application.getBean(DirectOrders::class.java)
            check(
                application.getBean("storeDirectOrderActors") is com.pawbridge.backend.user.internal.storeorder.infrastructure.JdbcStoreOrderActors,
                "Actual current User read actor must be reused",
            )
            released()
            nextAllowed = true
        }
    }

    @BeforeEach
    fun givenPreviousSuccess__whenNextStarts__thenRequireFullOrderedClass(info: TestInfo) {
        val prior = nextAllowed
        nextAllowed = false
        completedBody = false
        check(
            prior && info.testMethod.orElseThrow().getAnnotation(Order::class.java).value == nextOrder,
            "Full P21 class and previous success are mandatory",
        )
        released()
    }

    @AfterEach
    fun givenReadEnded__whenAudited__thenPreserveAllThirteenTables() {
        pool().recording = false
        released()
        check(
            baseline == fingerprints(),
            "Every source, User, stock, snapshot, document and Outbox row must remain unchanged",
        )
        check(completedBody, "Failed P21 body fences later work")
        nextOrder++
        nextAllowed = true
    }

    @AfterAll
    fun givenSettledReads__whenClosing__thenCloseContextBeforePoolAndRequireAllFiveCases() {
        try {
            poolRecordingOff()
            released()
            context?.close()
            val observedPool = pool
            if (observedPool != null) {
                released()
                observedPool.close()
                check(observedPool.isClosed, "Owned SELECT-only pool must close")
            }
            check(nextOrder == 6 && nextAllowed, "Exactly five complete P21 cases are required")
        } finally {
            levels.forEach { (logger, level) -> logger.level = level }
        }
    }

    @Test
    @Order(1)
    fun `내 주문과 타인 주문이 주어졌을 때__상태가 없거나 enum이면__모든 결과를 검증된 소유자로 한정한다`() {
        contract("owner and status") {
            val all = list(910001, null, request(0, 20))
            check(
                all.totalElements == 7L && HashSet(ids(all)) == setOf(101L, 102L, 103L, 104L, 105L, 106L, 107L),
                "Absent status includes only this owner's seven orders",
            )
            for (status in listOf("PENDING", "PAID", "CANCELLED", "COMPLETED", "FAILED")) {
                val page = list(910001, status, request(0, 20))
                check(
                    page.totalElements == (if (status == "PAID") 3L else 1L) &&
                        page.content.all { row -> row.userId == 910001L && row.status == status },
                    "Every enum branch must preserve owner and selected status",
                )
            }
            val other = list(910002, "PAID", request(0, 20))
            check(ids(other) == listOf(201L), "Another verified member reads only their own order")
        }
    }

    @Test
    @Order(2)
    fun `같은 생성 시각과 여러 항목이 주어졌을 때__동적 정렬로 페이지를 읽으면__부모 페이지와 구매 스냅샷을 보존한다`() {
        contract("page snapshots and sort") {
            val request = StoreMemberReads.Request(0, 2, listOf(StoreMemberReads.Order("totalAmount", true, false)))
            val page = list(910001, null, request)
            check(
                ids(page) == listOf(102L, 103L) && page.totalElements == 7L && page.size == 2 && page.number == 0,
                "CreatedAt DESC remains primary and dynamic amount sort resolves the tied parent rows",
            )
            check(
                page.sort == request.sort() &&
                    page.content.all { row -> storedCreatedAt(row.orderId!!) == row.createdAt },
                "Page metadata and static/dynamic ordering must retain the actual timestamps",
            )
            val first = page.content.first()
            val second = page.content.last()
            check(
                first.items!!.size == 2 && second.items!!.size == 3 &&
                    first.items!!.map { item -> item.price!! }.sorted() == listOf(100L, 200L) &&
                    first.items!!.all { item ->
                        item.productName!!.startsWith("p21 snapshot102") && item.skuCode!!.startsWith("p21-snap-102")
                    },
                "Child snapshots are complete and differ from live SKU/product values without parent fanout",
            )
            val next = list(910001, null, StoreMemberReads.Request(1, 2, request.sort()))
            check(
                ids(next) == listOf(101L, 104L) && next.totalElements == 7L,
                "Next page advances parent orders rather than item rows",
            )
            val empty = list(910001, null, StoreMemberReads.Request(9, 2, request.sort()))
            check(empty.content.isEmpty() && empty.totalElements == 7L, "An empty later page preserves the owner total")
        }
    }

    @Test
    @Order(3)
    fun `회원이 없거나 탈퇴 대기일 때__목록을 읽으면__주문 SQL 전에 거절한다`() {
        contract("current actor") {
            for (user in listOf(910003L, 910099L)) {
                pool().samples.clear()
                pool().recording = true
                try {
                    expect(OrderActorRejectedException::class.java) { orders.list(user, null, request(0, 2)) }
                } finally {
                    pool().recording = false
                }
                check(
                    pool().samples.count { sample -> sample.user } == 1 &&
                        pool().samples.none { sample -> !sample.user },
                    "Missing or deletion-pending current User must be rejected after only one actor lookup",
                )
                released()
            }
        }
    }

    @Test
    @Order(4)
    fun `작은 부모 페이지와 큰 부모 페이지가 주어졌을 때__실제로 읽으면__읽기 전용 쓰기 연결 하나와 스토어 쿼리 세 개 이하를 쓴다`() {
        contract("RO identity and query bound") {
            for (size in listOf(2, 4)) {
                list(910001, null, request(0, size))
                val samples = ArrayList(pool().samples)
                check(
                    samples.count { sample -> sample.user } == 1 &&
                        samples.count { sample -> !sample.user } == 3 &&
                        samples.count { sample -> sample.type == QueryType.PARENT } == 1 &&
                        samples.count { sample -> sample.type == QueryType.COUNT } == 1 &&
                        samples.count { sample -> sample.type == QueryType.BULK } == 1,
                    "Each full parent page observes one User lookup, one parent page, one count and one bulk item fetch",
                )
                val user = samples.first { sample -> sample.user }
                check(
                    user.pid > 0 && user.bound != null && user.serverReadOnly &&
                        samples.all { sample ->
                            sample.transaction && sample.readOnly && sample.manualCommit &&
                                sample.pid == user.pid && sample.bound === user.bound
                        },
                    "Actual PostgreSQL RO and the same writer PID/resource must span actor, parents and all children",
                )
            }
        }
    }

    @Test
    @Order(5)
    fun `실제 바깥 쓰기 트랜잭션이 주어졌을 때__읽기 파사드가 합류하면__잘못된 모드를 거절하고 모든 자원을 돌려준다`() {
        contract("outer mode failure") {
            val writes = context!!.getBean("storeDirectOrderWrites", TransactionTemplate::class.java)
            pool().samples.clear()
            pool().recording = true
            try {
                expect(IllegalStateException::class.java) {
                    writes.executeWithoutResult { orders.list(910001, null, request(0, 2)) }
                }
            } finally {
                pool().recording = false
            }
            check(pool().samples.isEmpty(), "Wrong outer mode must fail before User or order SQL")
            released()
            check(
                list(910001, "PENDING", request(0, 2)).content.size == 1,
                "A fresh subsequent actual RO call must recover after the rejected outer transaction",
            )
        }
    }

    private fun list(user: Long, status: String?, request: StoreMemberReads.Request): StoreMemberReads.Page<DirectOrders.OrderDetails> {
        pool().samples.clear()
        pool().recording = true
        try {
            return orders.list(user, status, request)
        } finally {
            pool().recording = false
        }
    }

    private fun ids(page: StoreMemberReads.Page<DirectOrders.OrderDetails>): List<Long> {
        return page.content.map { order -> order.orderId!! }
    }

    private fun storedCreatedAt(id: Long): LocalDateTime? {
        return jdbc.queryForObject(
            "SELECT created_at FROM pawbridge_store.orders WHERE id=?",
            RowMapper { row, _ -> row.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toLocalDateTime() },
            id,
        )
    }

    private fun fingerprints(): Map<String, String> {
        val values = TreeMap<String, String>()
        KEYS.forEach { (table, key) ->
            values[table] = jdbc.queryForObject(
                "SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY " + key + "),'')) FROM " + table + " t",
                String::class.java,
            )!!
        }
        return values
    }

    private fun poolRecordingOff() {
        pool?.recording = false
    }

    private fun released() {
        val observedPool = pool
        check(
            !TransactionSynchronizationManager.isActualTransactionActive() &&
                (
                    observedPool == null ||
                        (
                            !TransactionSynchronizationManager.hasResource(observedPool) &&
                                observedPool.borrows.get() == 0 &&
                                observedPool.hikariPoolMXBean.activeConnections == 0
                            )
                    ),
            "Owned read borrows and transactions must be returned before later work or close",
        )
    }

    private fun contract(phase: String, body: Checked) {
        observed(phase) {
            body.run()
            completedBody = true
        }
    }

    private fun verifyTarget() {
        val id = jdbc.queryForMap(
            "SELECT current_database() db,current_user actor,session_user session,current_setting('server_version_num') version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) owner",
        )
        check(
            settings.db == id["db"] && settings.user == id["actor"] && settings.user == id["session"] &&
                settings.owner() == id["owner"] && "170011" == id["version"],
            "Only exact fresh P21 role/database/server are accepted",
        )
        check(
            settings.marker == jdbc.queryForObject("SELECT marker FROM public.pb_store_history_fixture", String::class.java),
            "P21 marker must match",
        )
        check(
            true == jdbc.queryForObject(
                "SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=pg_roles.oid) FROM pg_roles WHERE rolname=current_user",
                Boolean::class.javaObjectType,
            ),
            "History runtime cannot own or inherit administrative capability",
        )
        check(
            true == jdbc.queryForObject(
                "SELECT NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit FROM pg_roles WHERE rolname=?",
                Boolean::class.javaObjectType,
                settings.owner(),
            ) &&
                false == jdbc.queryForObject(
                    "SELECT pg_has_role(current_user,?,'MEMBER')",
                    Boolean::class.javaObjectType,
                    settings.owner(),
                ),
            "Separate owner remains inaccessible NOLOGIN",
        )
        check(
            false == jdbc.queryForObject(
                "SELECT has_database_privilege(current_user,current_database(),'CREATE,TEMP')",
                Boolean::class.javaObjectType,
            ),
            "Runtime database/TEMP DDL forbidden",
        )
        for (schema in listOf("public", "pawbridge_store", "pawbridge_user")) {
            check(
                true == jdbc.queryForObject(
                    "SELECT has_schema_privilege(current_user,?,'USAGE') AND NOT has_schema_privilege(current_user,?,'CREATE')",
                    Boolean::class.javaObjectType,
                    schema,
                    schema,
                ),
                "Runtime schema creation forbidden",
            )
        }
        check(
            jdbc.query(
                "SELECT schemaname||'.'||tablename FROM pg_tables WHERE schemaname IN('pawbridge_store','pawbridge_user') ORDER BY 1",
                RowMapper { row, _ -> row.getString(1) },
            ) == KEYS.keys.sorted(),
            "Exact original thirteen tables are mandatory",
        )
        for (table in KEYS.keys) {
            for (right in listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                check(
                    (right == "SELECT") == jdbc.queryForObject(
                        "SELECT has_table_privilege(current_user,?,?)",
                        Boolean::class.javaObjectType,
                        table,
                        right,
                    ),
                    "Every history table remains SELECT-only",
                )
                if (setOf("INSERT", "UPDATE", "REFERENCES").contains(right)) {
                    check(
                        false == jdbc.queryForObject(
                            "SELECT has_any_column_privilege(current_user,?,?)",
                            Boolean::class.javaObjectType,
                            table,
                            right,
                        ),
                        "No column grant may bypass read-only rights",
                    )
                }
            }
        }
        check(
            true == jdbc.queryForObject(
                "SELECT has_table_privilege(current_user,'public.pb_store_history_fixture','SELECT') AND NOT has_table_privilege(current_user,'public.pb_store_history_fixture','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')",
                Boolean::class.javaObjectType,
            ),
            "Marker SELECT-only is mandatory",
        )
        val sequences = jdbc.query(
            "SELECT n.nspname||'.'||c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='pawbridge_store' AND c.relkind='S' ORDER BY 1",
            RowMapper { row, _ -> row.getString(1) },
        )
        check(sequences.size == 10, "Only original Store ten identities may exist")
        for (sequence in sequences) {
            check(
                false == jdbc.queryForObject(
                    "SELECT has_sequence_privilege(current_user,?,'USAGE,SELECT,UPDATE')",
                    Boolean::class.javaObjectType,
                    sequence,
                ),
                "History runtime cannot use source sequences",
            )
        }
        check(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN('public','pawbridge_store','pawbridge_user') AND c.relkind IN('r','S','v') AND pg_get_userbyid(c.relowner)<>?",
                Long::class.javaObjectType,
                settings.owner(),
            ) == 0L,
            "Every new fixture object keeps the separate owner",
        )
    }

    private fun quiet() {
        for (name in listOf(
            "com.zaxxer.hikari",
            "org.hibernate",
            "org.springframework.jdbc",
            "org.springframework.orm.jpa",
            "org.springframework.context",
        )) {
            val logger = LoggerFactory.getLogger(name) as ch.qos.logback.classic.Logger
            levels[logger] = logger.level
            logger.level = ch.qos.logback.classic.Level.OFF
        }
    }

    private fun interface Checked {
        fun run()
    }

    private enum class QueryType { USER, PARENT, COUNT, BULK, OTHER_STORE }

    private class Sample(
        val user: Boolean,
        val type: QueryType,
        val pid: Int,
        val transaction: Boolean,
        val readOnly: Boolean,
        val manualCommit: Boolean,
        val bound: Any?,
        val serverReadOnly: Boolean,
    )

    private class ObservedPool(config: HikariConfig) : HikariDataSource(config) {
        val borrows = AtomicInteger()
        val samples: MutableList<Sample> = CopyOnWriteArrayList()

        @Volatile
        var recording: Boolean = false

        override fun getConnection(): Connection {
            val target = super.getConnection()
            borrows.incrementAndGet()
            val closed = AtomicBoolean()
            val backend = intArrayOf(0)
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf<Class<*>>(Connection::class.java),
                InvocationHandler { _, method, args ->
                    if (method.name == "close") {
                        if (closed.compareAndSet(false, true)) {
                            try {
                                return@InvocationHandler call(target, method, args)
                            } finally {
                                borrows.decrementAndGet()
                            }
                        }
                        return@InvocationHandler null
                    }
                    val value = call(target, method, args)
                    if (value is PreparedStatement && args != null && args.isNotEmpty() && args[0] is String) {
                        val sql = args[0] as String
                        val normalized = sql.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
                        val user = normalized.contains("pawbridge_user.users")
                        val store = normalized.contains("pawbridge_store.")
                        val type = if (user) {
                            QueryType.USER
                        } else if (normalized.matches(Regex("(?s).*\\bcount\\s*\\(.*"))) {
                            QueryType.COUNT
                        } else if (normalized.contains("pawbridge_store.orders") &&
                            normalized.contains("pawbridge_store.order_items") &&
                            normalized.contains(" join ")
                        ) {
                            QueryType.BULK
                        } else if (normalized.contains("pawbridge_store.orders") &&
                            !normalized.contains("pawbridge_store.order_items")
                        ) {
                            QueryType.PARENT
                        } else {
                            QueryType.OTHER_STORE
                        }
                        return@InvocationHandler Proxy.newProxyInstance(
                            PreparedStatement::class.java.classLoader,
                            arrayOf<Class<*>>(PreparedStatement::class.java),
                            InvocationHandler { _, statementMethod, statementArgs ->
                                if (recording && statementMethod.name.startsWith("execute") && (user || store)) {
                                    var serverReadOnly = false
                                    // One explicit observer query per User lookup; excluded from application SQL counts.
                                    if (user) {
                                        target.createStatement().use { probe ->
                                            probe.queryTimeout = 3
                                            probe.executeQuery("SELECT pg_backend_pid(),current_setting('transaction_read_only')")
                                                .use { row ->
                                                    check(row.next(), "Server read-only probe must return")
                                                    backend[0] = row.getInt(1)
                                                    serverReadOnly = "on" == row.getString(2)
                                                }
                                        }
                                    }
                                    samples.add(
                                        Sample(
                                            user,
                                            type,
                                            backend[0],
                                            TransactionSynchronizationManager.isActualTransactionActive(),
                                            TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                                            !target.autoCommit,
                                            TransactionSynchronizationManager.getResource(this@ObservedPool),
                                            serverReadOnly,
                                        ),
                                    )
                                }
                                call(value, statementMethod, statementArgs)
                            },
                        )
                    }
                    value
                },
            ) as Connection
        }
    }

    private class Settings(
        val url: String,
        val db: String,
        val user: String,
        val password: String,
        val marker: String,
    ) {
        fun owner(): String = db.replace("pb_store_history_owned_", "pb_store_history_owner_")

        override fun toString(): String = "OwnedStoreHistorySettings[redacted]"

        companion object {
            fun load(): Settings {
                check("P21_PG_SETTINGS" == System.getenv("PAWBRIDGE_OWNED_SETTINGS_ENV"), "Only explicit P21 selector accepted")
                val configured = System.getenv("P21_PG_SETTINGS")
                check(configured != null && configured.isNotBlank(), "P21 settings are required without skip/fallback")
                val expected = Path.of("/private/tmp/pawbridge-owned-pg-night-20261020/store-order-history/store-order-history.properties")
                val file = Path.of(configured)
                check(
                    file == expected && file.isAbsolute && file.normalize() == file &&
                        Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file),
                    "Exact fresh P21 settings only",
                )
                var parent: Path? = file.parent
                while (parent != null) {
                    check(!Files.isSymbolicLink(parent), "No symlink settings ancestor")
                    parent = parent.parent
                }
                check(
                    Files.getPosixFilePermissions(file) == setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE) &&
                        (Files.getAttribute(file, "unix:nlink") as Number).toInt() == 1 &&
                        System.getProperty("user.name") == Files.getOwner(file).name &&
                        Files.getOwner(file) == Files.getOwner(file.parent),
                    "Private current-owner single-link0600 required",
                )
                check(
                    Files.getPosixFilePermissions(file.parent) ==
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                    "Exclusive claim0700 required",
                )
                val values = Properties()
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input -> values.load(input) }
                check(
                    values.stringPropertyNames() == setOf("jdbcUrl", "database", "username", "password", "marker"),
                    "Exactly five private credential fields accepted",
                )
                val db = required(values, "database")
                val user = required(values, "username")
                val url = required(values, "jdbcUrl")
                val marker = required(values, "marker")
                check(
                    db.matches(Regex("pb_store_history_owned_[0-9a-f]{12}")) &&
                        user == db.replace("pb_store_history_owned_", "pb_store_history_reader_") &&
                        url == "jdbc:postgresql://127.0.0.1:65309/" + db &&
                        marker.matches(Regex("[0-9a-f]{32}")),
                    "Only exact nonce reader/loopback65309/marker accepted",
                )
                val state = file.resolveSibling("state.json")
                check(
                    Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(state) &&
                        Files.getPosixFilePermissions(state) == setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE) &&
                        (Files.getAttribute(state, "unix:nlink") as Number).toInt() == 1 &&
                        Files.getOwner(state) == Files.getOwner(file),
                    "Exact private P21 state required",
                )
                val claim = ObjectMapper().readTree(Files.readAllBytes(state))
                check(
                    db == claim.path("database").asText() &&
                        user == claim.path("writer").asText() &&
                        marker == claim.path("marker").asText() &&
                        "store-order-history-v1" == claim.path("purpose").asText() &&
                        SQL_SHA == claim.path("schema_sha256").asText() &&
                        claim.path("schemaInitialized").asBoolean() &&
                        claim.path("port").asInt() == 65309 &&
                        "b1719e47b8c3ff8c334e9df96c86b4f36478eb72022b8e19278d75c9f8a7187c" == claim.path("containerId").asText() &&
                        "sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f" == claim.path("image").asText() &&
                        "08b97b84-e0b5-4c6f-a5f8-3d51ec4a2f89" == claim.path("owner").asText(),
                    "P21 state must bind the exact approved container, SQL and new claim",
                )
                return Settings(url, db, user, required(values, "password"), marker)
            }

            private fun required(values: Properties, key: String): String {
                val value = values.getProperty(key)
                check(value != null && value.isNotBlank(), "Every P21 setting is mandatory")
                return value
            }
        }
    }

    companion object {
        private const val SQL_SHA = "8ce980cb07524f7e8129c7f940744ee36dcdce5a3166b4d7c179e8f509b7cdea"
        private val KEYS: Map<String, String> = mapOf(
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

        private fun request(page: Int, size: Int): StoreMemberReads.Request {
            return StoreMemberReads.Request(page, size, listOf(StoreMemberReads.Order("id", true, false)))
        }

        private fun call(target: Any, method: Method, args: Array<Any?>?): Any? {
            try {
                return if (args == null) method.invoke(target) else method.invoke(target, *args)
            } catch (failure: InvocationTargetException) {
                throw failure.cause!!
            }
        }

        private fun observed(phase: String, body: Checked) {
            try {
                body.run()
            } catch (failure: AssertionError) {
                throw failure
            } catch (failure: Throwable) {
                throw AssertionError("Owned P21 failed: " + phase + "; type=" + failure.javaClass.name)
            }
        }

        private fun expect(type: Class<out Throwable>, body: Checked) {
            try {
                body.run()
            } catch (failure: Throwable) {
                check(type.isInstance(failure), "Only the expected refusal type is accepted")
                return
            }
            throw AssertionError("Expected refusal absent")
        }

        private fun check(condition: Boolean, fixed: String) {
            if (!condition) {
                throw AssertionError(fixed)
            }
        }

        private fun sha(bytes: ByteArray): String {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            } catch (impossible: Exception) {
                throw AssertionError("SHA unavailable")
            }
        }
    }
}
