package com.pawbridge.backend

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import com.pawbridge.backend.boot.configuration.PostWriteSettings
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.metrics.IMetricsTracker
import com.zaxxer.hikari.metrics.MetricsTrackerFactory
import jakarta.persistence.EntityManagerFactory
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
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.mock.web.MockServletContext
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.ConfigurableTransactionManager
import org.springframework.transaction.TransactionExecution
import org.springframework.transaction.TransactionExecutionListener
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.context.support.GenericWebApplicationContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.sql.SQLException
import java.util.HexFormat
import java.util.Properties
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** One fresh SELECT-only fixture, actual Main and module APIs; no business/DataSource substitutes. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(60)
class OwnedPostgresqlMainCoreIntegrationTest {
    private val json = ObjectMapper()
    private val resources = Resources()
    private val levels = LinkedHashMap<ch.qos.logback.classic.Logger, ch.qos.logback.classic.Level?>()
    private lateinit var settings: Settings
    private var context: GenericWebApplicationContext? = null
    private lateinit var reader: HikariDataSource
    private lateinit var writer: HikariDataSource
    private lateinit var audit: JdbcTemplate
    private lateinit var mvc: MockMvc
    private lateinit var baseline: Map<String, String>
    private var nextOrder = 1
    private var nextAllowed = false
    private var caseEntered = false
    private var completedBody = false

    @BeforeAll
    fun given__fresh_select_only_p22__when__actual_main_starts__then__use_exact_roles_and_resources() {
        observed("setup") {
            quiet()
            settings = Settings.load(json)
            javaClass.getResourceAsStream("/owned-postgresql/main-core-owned-schema.sql").use { input ->
                check(input != null && sha(input.readAllBytes()) == SQL_SHA, "Exact frozen P22 schema is required")
            }
            val values = F3TestInputs.values()
            values["PAWBRIDGE_DB_URL"] = settings.url
            values["PAWBRIDGE_DB_USER"] = settings.reader
            values["PAWBRIDGE_DB_PASSWORD"] = settings.password
            values["PAWBRIDGE_DB_POOL_MAX_SIZE"] = "1"
            val read = ReadDatabaseSettings.fromEnvironment(values::get)
            val write = PostWriteSettings.fromEnvironment(values::get)
            val application = GenericWebApplicationContext()
            context = application
            application.setServletContext(MockServletContext())
            TestPropertyValues.of(*CORE_FLAGS).applyTo(application)
            application.beanFactory.registerSingleton("readDatabaseSettings", read)
            application.beanFactory.registerSingleton("postWriteSettings", write)
            application.beanFactory.addBeanPostProcessor(resources)
            AnnotatedBeanDefinitionReader(application).register(PawBridgeApplication::class.java)
            application.refresh()
            reader = application.getBean("dataSource", HikariDataSource::class.java)
            writer = application.getBean("writeDataSource", HikariDataSource::class.java)
            check(
                reader !== writer && resources.pools.size == 2 && resources.factories.size == 3,
                "Actual Main must own the two named pools and three factories",
            )
            check(
                reader.username == settings.reader && writer.username == settings.writer &&
                    reader.jdbcUrl == settings.url && writer.jdbcUrl == settings.url,
                "Actual Main settings must select the two distinct P22 identities on the same database",
            )
            audit = JdbcTemplate(reader)
            audit.queryTimeout = 5
            verifyTarget(reader, false)
            verifyTarget(writer, true)
            check(
                audit.query(
                    "SELECT schemaname||'.'||tablename FROM pg_tables WHERE schemaname IN " +
                        "('pawbridge_store','pawbridge_user','pawbridge_animal','pawbridge_community') ORDER BY 1",
                    RowMapper { row, _ -> row.getString(1) },
                ) == KEYS.keys.sorted(),
                "Only the exact seventeen selected source tables may exist",
            )
            baseline = fingerprints()
            mvc = MockMvcBuilders.webAppContextSetup(application)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
            released()
            nextAllowed = true
        }
    }

    @BeforeEach
    fun given__previous_case_succeeded__when__next_case_starts__then__fence_partial_or_failed_runs(info: TestInfo) {
        val previous = nextAllowed
        nextAllowed = false
        completedBody = false
        caseEntered = false
        check(
            previous && info.testMethod.orElseThrow().getAnnotation(Order::class.java).value == nextOrder,
            "Full ordered P22 class and previous success are mandatory",
        )
        released()
        resources.events.clear()
        caseEntered = true
    }

    @AfterEach
    fun given__request_ended__when__auditing__then__preserve_all_tables_and_return_every_resource() {
        if (!caseEntered) {
            return
        }
        released()
        check(baseline == fingerprints(), "Every selected source row must remain unchanged")
        released()
        check(completedBody, "Failed P22 request fences all later requests")
        nextOrder++
        nextAllowed = true
    }

    @AfterAll
    fun given__requests_settled__when__main_closes__then__close_owned_resources_without_dropping_data() {
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!resources.idle() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(20)
                } catch (stopped: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            check(
                resources.idle() && !TransactionSynchronizationManager.isActualTransactionActive() &&
                    resources.pools.none { pool -> TransactionSynchronizationManager.hasResource(pool) },
                "Active requests remain; do not close their pools or discard the fixture",
            )
            context?.close()
            check(resources.pools.all { pool -> pool.isClosed }, "Actual Main pools must close")
            check(resources.factories.none { factory -> factory.isOpen }, "Actual Main factories must close")
            for (analyzer in resources.animalAnalyzers) {
                expectClosed { analyzer.tokens("진돗개") }
            }
            for (analyzer in resources.storeAnalyzers) {
                expectClosed { analyzer.query("강아지 사료") }
            }
            check(nextOrder == 4 && nextAllowed, "Exactly all three P22 requests must complete")
        } finally {
            levels.forEach { (logger, level) -> logger.level = level }
        }
    }

    @Test
    @Order(1)
    fun `본인과 타인의 즐겨찾기가 있을 때__JWT로 사용자 모듈에 들어가면__독립 리더로 실제 동물 배치를 읽는다`() {
        // given
        // The owned fixture rows are seeded and fenced by the class-level setup.
        contract("User Animal read") {
            // when
            val data = success("/api/v1/users/me/favorite-animals")

            // then
            check(
                data.path("userId").asLong() == 910001L && data.path("totalCount").asInt() == 1 &&
                    data.path("favorites").size() == 1,
                "Only the authenticated member's favorite may return",
            )
            val item = data.path("favorites").get(0)
            check(
                item.path("favoriteId").asLong() == 510001L && item.path("animalId").asLong() == 420001L &&
                    item.path("breed").asText() == "p22-breed-a" &&
                    item.path("shelterName").asText() == "p22-shelter-a" &&
                    item.path("status").asText() == "PROTECT",
                "Actual module batch and joined shelter must supply the selected Animal data",
            )
            val writes = begins("writeTransactionManager")
            val reads = begins("transactionManager")
            check(
                writes.size == 1 && reads.size == 1 &&
                    writes.first().actor == settings.writer && reads.first().actor == settings.reader &&
                    writes.first().pid != reads.first().pid,
                "User RO writer transaction and Animal independent reader must use distinct real connections",
            )
            check(resources.events.all { event -> event.readOnly }, "Both actual module transactions must be read-only")
        }
    }

    @Test
    @Order(2)
    fun `실제 사용자와 구매 스냅샷이 있을 때__JWT로 마이페이지를 읽으면__사용자 조회를 끝낸 뒤 스토어 읽기 전용 페이지를 읽는다`() {
        // given
        // The owned fixture rows are seeded and fenced by the class-level setup.
        contract("User Store read") {
            // when
            val page = success("/api/v1/users/me/orders?size=20&sort=id,asc")

            // then
            check(
                page.path("totalElements").asLong() == 7L && page.path("content").size() == 7,
                "Only this member's seven purchase parents may return",
            )
            val ids = HashSet<Long>()
            var selected: JsonNode? = null
            for (row in page.path("content")) {
                ids.add(row.path("orderId").asLong())
                if (row.path("orderId").asLong() == 102L) {
                    selected = row
                }
            }
            check(
                ids == setOf(101L, 102L, 103L, 104L, 105L, 106L, 107L) && selected != null,
                "Another member's later order must never enter the User page",
            )
            check(
                selected!!.path("items").size() == 2 && selected.path("totalAmount").asLong() == 300L,
                "Purchase snapshots must retain both selected items and their original amount",
            )
            val prices = TreeSet<Long>()
            for (item in selected.path("items")) {
                prices.add(item.path("price").asLong())
                check(
                    item.path("productName").asText().startsWith("p21 snapshot102"),
                    "Live product data cannot replace purchase snapshots",
                )
            }
            check(prices == setOf(100L, 200L), "Purchase item prices must retain their exact snapshots")
            val user = begins("transactionManager")
            val store = begins("storeMemberJpaTransactionManager")
            check(
                user.size == 1 && store.size == 1 && store.first().isolation == "repeatable read",
                "Actual User lookup and Store reader JPA/RR must each own their read transaction",
            )
            check(
                index("transactionManager", "commit") < index("storeMemberJpaTransactionManager", "begin"),
                "User transaction completion must precede the Store module call",
            )
        }
    }

    @Test
    @Order(3)
    fun `커뮤니티 작성자와 실제 사용자 닉네임이 있을 때__게시글을 읽으면__대체값 없이 모듈 간 조회를 한다`() {
        // given
        // The owned fixture rows are seeded and fenced by the class-level setup.
        contract("Community User read") {
            // when
            val post = success("/api/v1/posts/read/810001")

            // then
            check(
                post.path("postId").asLong() == 810001L && post.path("authorId").asLong() == 910001L &&
                    post.path("authorNickname").asText() == "p22-nick-910001" &&
                    post.path("title").asText() == "p22-post-a",
                "Actual User nickname must be returned rather than the unavailable-user fallback",
            )
            check(
                begins("transactionManager").size == 2 &&
                    resources.events.all { event -> event.actor == settings.reader && event.readOnly },
                "Community post and User nickname must complete actual reader transactions",
            )
        }
    }

    private fun success(path: String): JsonNode {
        val response = mvc.perform(
            get(path).header("Authorization", "Bearer " + F3TestInputs.token(910001, "ROLE_USER")),
        )
            .andExpect(status().isOk())
            .andReturn()
        check(response.handler != null, "Actual MVC handler must consume the successful request")
        val result = json.readTree(response.response.contentAsByteArray)
        check(result.path("code").asInt() == 200, "Existing successful response envelope must be preserved")
        return result.path("data")
    }

    private fun begins(manager: String): List<Event> {
        return resources.events.filter { event -> event.manager == manager && event.phase == "begin" }
    }

    private fun index(manager: String, phase: String): Int {
        for (i in 0 until resources.events.size) {
            if (resources.events[i].manager == manager && resources.events[i].phase == phase) {
                return i
            }
        }
        throw AssertionError("Required actual transaction event is absent")
    }

    private fun fingerprints(): Map<String, String> {
        val result = TreeMap<String, String>()
        KEYS.forEach { (table, key) ->
            result[table] = audit.queryForObject(
                "SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY " + key + "),'')) FROM " + table + " t",
                String::class.java,
            )!!
        }
        return result
    }

    private fun released() {
        check(
            !TransactionSynchronizationManager.isActualTransactionActive() && resources.idle() &&
                resources.timeouts.get() == 0 &&
                resources.pools.none { pool -> TransactionSynchronizationManager.hasResource(pool) },
            "Every actual Main transaction and pool borrow must return before later work",
        )
    }

    private fun verifyTarget(source: HikariDataSource, writingName: Boolean) {
        val jdbc = JdbcTemplate(source)
        jdbc.queryTimeout = 5
        val row = jdbc.queryForMap(
            "SELECT current_database() db,current_user actor,session_user session,current_setting('server_version_num') version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) owner",
        )
        val actor = if (writingName) settings.writer else settings.reader
        check(
            settings.db == row["db"] && actor == row["actor"] && actor == row["session"] &&
                settings.owner() == row["owner"] && "170011" == row["version"],
            "Exact P22 database, role and PG17.11 are mandatory",
        )
        check(
            settings.marker == jdbc.queryForObject("SELECT marker FROM public.pb_main_core_fixture", String::class.java),
            "Exact P22 marker is required",
        )
        check(
            true == jdbc.queryForObject(
                "SELECT has_table_privilege(current_user,'public.pb_main_core_fixture','SELECT') AND NOT has_table_privilege(current_user,'public.pb_main_core_fixture','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')",
                Boolean::class.javaObjectType,
            ),
            "Marker remains SELECT-only",
        )
        check(
            true == jdbc.queryForObject(
                "SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=pg_roles.oid) FROM pg_roles WHERE rolname=current_user",
                Boolean::class.javaObjectType,
            ),
            "Runtime cannot own or inherit administrative capability",
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
            "Separate NOLOGIN owner must remain inaccessible",
        )
        check(
            false == jdbc.queryForObject(
                "SELECT has_database_privilege(current_user,current_database(),'CREATE,TEMP')",
                Boolean::class.javaObjectType,
            ),
            "Runtime database and TEMP DDL are forbidden",
        )
        for (schema in listOf("public", "pawbridge_user", "pawbridge_store", "pawbridge_animal", "pawbridge_community")) {
            check(
                false == jdbc.queryForObject(
                    "SELECT has_schema_privilege(current_user,?,'CREATE')",
                    Boolean::class.javaObjectType,
                    schema,
                ),
                "Runtime schema creation is forbidden",
            )
        }
        for (table in KEYS.keys) {
            for (right in listOf("INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                check(
                    false == jdbc.queryForObject(
                        "SELECT has_table_privilege(current_user,(SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname||'.'||c.relname=?),?)",
                        Boolean::class.javaObjectType,
                        table,
                        right,
                    ),
                    "Selected tables must have no runtime DML rights",
                )
            }
            for (right in listOf("INSERT", "UPDATE", "REFERENCES")) {
                check(
                    false == jdbc.queryForObject(
                        "SELECT has_any_column_privilege(current_user,(SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname||'.'||c.relname=?),?)",
                        Boolean::class.javaObjectType,
                        table,
                        right,
                    ),
                    "Column grants cannot bypass read-only access",
                )
            }
            val select = true == jdbc.queryForObject(
                "SELECT has_table_privilege(current_user,(SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname||'.'||c.relname=?),'SELECT')",
                Boolean::class.javaObjectType,
                table,
            )
            check(select == (!writingName || table == "pawbridge_user.favorites"), "Only each identity's selected read tables may be accessible")
        }
        check(
            true == jdbc.queryForObject(
                "SELECT has_column_privilege(current_user,'pawbridge_user.users','user_id','SELECT')",
                Boolean::class.javaObjectType,
            ),
            "Writer User existence lookup must be available",
        )
        for (column in listOf("email", "name", "nickname", "provider", "role", "created_at")) {
            check(
                !writingName == jdbc.queryForObject(
                    "SELECT has_column_privilege(current_user,'pawbridge_user.users',?,'SELECT')",
                    Boolean::class.javaObjectType,
                    column,
                ),
                "Writer must not inherit unrelated User profile columns",
            )
        }
        val sequences = jdbc.query(
            "SELECT n.nspname||'.'||c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN('pawbridge_store','pawbridge_user','pawbridge_animal','pawbridge_community') AND c.relkind='S'",
            RowMapper { rs, _ -> rs.getString(1) },
        )
        check(sequences.size == 13, "Exactly selected identity sequences are required")
        for (sequence in sequences) {
            check(
                false == jdbc.queryForObject(
                    "SELECT has_sequence_privilege(current_user,(SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname||'.'||c.relname=?),'USAGE,SELECT,UPDATE')",
                    Boolean::class.javaObjectType,
                    sequence,
                ),
                "Neither runtime identity may use source sequences",
            )
        }
        check(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN('public','pawbridge_store','pawbridge_user','pawbridge_animal','pawbridge_community') AND c.relkind IN('r','S','v') AND pg_get_userbyid(c.relowner)<>?",
                Long::class.javaObjectType,
                settings.owner(),
            ) == 0L,
            "All new fixture objects retain their inaccessible owner",
        )
    }

    private fun contract(phase: String, work: Checked) {
        observed(phase) {
            work.run()
            completedBody = true
        }
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

    private class Event(
        val manager: String,
        val phase: String,
        val pid: Int,
        val actor: String,
        val readOnly: Boolean,
        val isolation: String,
    )

    private inner class Resources : BeanPostProcessor {
        val pools: MutableList<HikariDataSource> = CopyOnWriteArrayList()
        val factories: MutableList<EntityManagerFactory> = CopyOnWriteArrayList()
        val animalAnalyzers: MutableList<KoreanSearchAnalyzer> = CopyOnWriteArrayList()
        val storeAnalyzers: MutableList<KoreanSearchTerms> = CopyOnWriteArrayList()
        val events: MutableList<Event> = CopyOnWriteArrayList()
        val active = AtomicInteger()
        val timeouts = AtomicInteger()

        override fun postProcessAfterInitialization(bean: Any, name: String): Any {
            if (bean is HikariDataSource) {
                pools.add(bean)
                bean.metricsTrackerFactory = MetricsTrackerFactory { _, _ ->
                    object : IMetricsTracker {
                        override fun recordConnectionAcquiredNanos(elapsed: Long) {
                            active.incrementAndGet()
                        }

                        override fun recordConnectionUsageMillis(elapsed: Long) {
                            active.decrementAndGet()
                        }

                        override fun recordConnectionTimeout() {
                            timeouts.incrementAndGet()
                        }
                    }
                }
            }
            if (bean is EntityManagerFactory) {
                factories.add(bean)
            }
            if (bean is KoreanSearchAnalyzer) {
                animalAnalyzers.add(bean)
            }
            if (bean is KoreanSearchTerms) {
                storeAnalyzers.add(bean)
            }
            if (bean is ConfigurableTransactionManager) {
                val manager: ConfigurableTransactionManager = bean
                val source: HikariDataSource? = if (bean is JdbcTransactionManager) {
                    bean.dataSource as HikariDataSource
                } else if (bean is JpaTransactionManager) {
                    bean.dataSource as HikariDataSource
                } else {
                    null
                }
                if (source != null) {
                    manager.addListener(
                        object : TransactionExecutionListener {
                            override fun afterBegin(tx: TransactionExecution, failure: Throwable?) {
                                if (failure != null) {
                                    return
                                }
                                val bound = TransactionSynchronizationManager.getResource(source)
                                check(bound is ConnectionHolder, "Actual transaction must bind the named datasource")
                                try {
                                    (bound as ConnectionHolder).connection.createStatement().use { statement ->
                                        statement.queryTimeout = 3
                                        statement.executeQuery(
                                            "SELECT current_database(),current_user,pg_backend_pid(),current_setting('transaction_read_only'),current_setting('transaction_isolation')",
                                        ).use { row ->
                                            check(
                                                row.next() && settings.db == row.getString(1) && tx.isReadOnly &&
                                                    "on" == row.getString(4) && !bound.connection.autoCommit,
                                                "Actual transaction must select this P22 database read-only",
                                            )
                                            events.add(Event(name, "begin", row.getInt(3), row.getString(2), true, row.getString(5)))
                                        }
                                    }
                                } catch (failed: SQLException) {
                                    throw AssertionError("Read-only transaction probe failed")
                                }
                            }

                            override fun afterCommit(tx: TransactionExecution, failure: Throwable?) {
                                check(failure == null, "Main request transaction commit must succeed")
                                events.add(Event(name, "commit", 0, source.username, tx.isReadOnly, ""))
                            }
                        },
                    )
                }
            }
            return bean
        }

        fun idle(): Boolean {
            return active.get() == 0 &&
                pools.all { p -> p.hikariPoolMXBean == null || p.hikariPoolMXBean.activeConnections == 0 }
        }
    }

    private class Settings(
        val url: String,
        val db: String,
        val reader: String,
        val password: String,
        val writer: String,
        val writerPassword: String,
        val marker: String,
    ) {
        fun owner(): String = db.replace("pb_main_core_owned_", "pb_main_core_owner_")

        override fun toString(): String = "OwnedMainSettings[redacted]"

        companion object {
            fun load(json: ObjectMapper): Settings {
                check("P22_PG_SETTINGS" == System.getenv("PAWBRIDGE_OWNED_SETTINGS_ENV"), "Only explicit P22 selector is accepted")
                val configured = System.getenv("P22_PG_SETTINGS")
                check(configured != null && configured.isNotBlank(), "P22 settings are mandatory without skip or fallback")
                val file = Path.of(configured)
                val expected = Path.of("/private/tmp/pawbridge-owned-pg-night-20261020/main-core/main-core.properties")
                check(file == expected && file.normalize() == file, "Only exact fresh Main settings path is accepted")
                privateFile(file)
                privateFile(RESUME)
                check(sha(Files.readAllBytes(RESUME)) == RESUME_SHA, "Only the current Root resume receipt is accepted")
                val resume = json.readTree(Files.readAllBytes(RESUME))
                check(
                    CID == resume.path("cid").asText() && IMAGE == resume.path("image").asText() &&
                        OWNER == resume.path("owner").asText() && "127.0.0.1" == resume.path("host").asText() &&
                        resume.path("port").asInt() == 55437 && resume.path("ready").asBoolean() &&
                        !resume.path("oldTmpfsRowsReused").asBoolean() && resume.path("noHostMount").asBoolean(),
                    "Current Root owned identity must match",
                )
                check(
                    Files.getPosixFilePermissions(file.parent) ==
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                    "Exclusive claim0700 required",
                )
                val properties = Properties()
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input -> properties.load(input) }
                check(
                    properties.stringPropertyNames() ==
                        setOf("jdbcUrl", "database", "username", "password", "writerUsername", "writerPassword", "marker"),
                    "Exactly seven private fields are accepted",
                )
                val db = required(properties, "database")
                val reader = required(properties, "username")
                val writer = required(properties, "writerUsername")
                val marker = required(properties, "marker")
                val url = required(properties, "jdbcUrl")
                check(
                    db.matches(Regex("pb_main_core_owned_[0-9a-f]{12}")) &&
                        reader == db.replace("pb_main_core_owned_", "pb_main_core_reader_") &&
                        writer == db.replace("pb_main_core_owned_", "pb_main_core_writer_") && reader != writer &&
                        url == "jdbc:postgresql://127.0.0.1:55437/" + db && marker.matches(Regex("[0-9a-f]{32}")),
                    "Only exact nonce roles/database/loopback/marker are accepted",
                )
                val state = file.resolveSibling("state.json")
                privateFile(state)
                val claim = json.readTree(Files.readAllBytes(state))
                check(
                    db == claim.path("database").asText() && reader == claim.path("reader").asText() &&
                        writer == claim.path("writer").asText() && marker == claim.path("marker").asText() &&
                        "main-core-v1" == claim.path("purpose").asText() && claim.path("schemaInitialized").asBoolean() &&
                        SQL_SHA == claim.path("schema_sha256").asText() &&
                        RESUME_SHA == claim.path("resume_receipt_sha256").asText() && claim.path("port").asInt() == 55437 &&
                        CID == claim.path("containerId").asText() && IMAGE == claim.path("image").asText() &&
                        OWNER == claim.path("owner").asText(),
                    "Fresh claim must bind exact SQL/Root receipt and owned runtime",
                )
                return Settings(url, db, reader, required(properties, "password"), writer, required(properties, "writerPassword"), marker)
            }

            private fun required(p: Properties, key: String): String {
                val value = p.getProperty(key)
                check(value != null && value.isNotBlank(), "Every private P22 setting is mandatory")
                return value
            }
        }
    }

    companion object {
        private const val SQL_SHA = "c29b9adc5c085fd8d31d1ab3eb019a369d64d29f73debfbf78055319ccfaa294"
        private const val RESUME_SHA = "0c471fd664f107524e395b0d414c3e1a1c107f007e1f512194f0562f97c91e47"
        private const val CID = "b1719e47b8c3ff8c334e9df96c86b4f36478eb72022b8e19278d75c9f8a7187c"
        private const val IMAGE = "sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f"
        private const val OWNER = "08b97b84-e0b5-4c6f-a5f8-3d51ec4a2f89"
        private val RESUME: Path = Path.of("/private/tmp/pawbridge-main-db-integration-owned-20261021/owned-resume-receipt.json")
        private val KEYS: Map<String, String> = mapOf(
            "pawbridge_user.users" to "user_id",
            "pawbridge_user.contact_deletions" to "user_id",
            "pawbridge_user.favorites" to "favorite_id",
            "pawbridge_community.posts" to "post_id",
            "pawbridge_animal.shelters" to "id",
            "pawbridge_animal.animals" to "id",
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
        private val CORE_FLAGS: Array<String> = arrayOf(
            "spring.sql.init.mode=never",
            "spring.jmx.enabled=false",
            "pawbridge.database.writer.maximum-pool-size=1",
            "pawbridge.user-animal-activities.enabled=true",
            "pawbridge.user-store-mypage.enabled=true",
            "pawbridge.animal-commands.enabled=true",
            "pawbridge.store-direct-orders.enabled=true",
            "pawbridge.auth.google-enabled=false",
            "pawbridge.auth.sessions-enabled=false",
            "pawbridge.chat.enabled=false",
            "pawbridge.jobs.cleanup-enabled=false",
            "pawbridge.user-maintenance.enabled=false",
            "pawbridge.community-maintenance.enabled=false",
            "pawbridge.visits.enabled=false",
            "pawbridge.visits.collection-enabled=false",
            "pawbridge.animal-query.projection-schedule-enabled=false",
            "apms.batch.schedule-enabled=false",
            "apms-photo-archive.enabled=false",
            "tourapi.enabled=false",
            "tourapi.schedule-enabled=false",
            "lost-gallery-feed.enabled=false",
            "pawbridge.animal-lost-search.enabled=false",
            "pawbridge.store-carts.enabled=false",
            "pawbridge.store-catalog.enabled=false",
            "pawbridge.store-order-administration.enabled=false",
            "pawbridge.animal-discovery.enabled=false",
            "pawbridge.animal-recommendations.enabled=false",
            "pawbridge.animal-shelters.enabled=false",
            "pawbridge.animal-statistics.enabled=false",
            "pawbridge.animal-travel.enabled=false",
            "pawbridge.shelter-commands.enabled=false",
            "pawbridge.animal-reports.enabled=false",
            "pawbridge.store-wishlist.enabled=false",
            "pawbridge.shelter-membership.enabled=false",
            "pawbridge.home-videos.management-enabled=false",
        )

        private fun privateFile(path: Path) {
            check(
                path.isAbsolute && path.normalize() == path && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
                "Exact regular private file is required",
            )
            var p: Path? = path
            while (p != null) {
                check(!Files.isSymbolicLink(p), "No private-file symlink ancestor is allowed")
                p = p.parent
            }
            check(
                Files.getPosixFilePermissions(path) == setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE) &&
                    (Files.getAttribute(path, "unix:nlink") as Number).toInt() == 1 &&
                    System.getProperty("user.name") == Files.getOwner(path).name,
                "Current-owner single-link0600 file is required",
            )
        }

        private fun observed(phase: String, work: Checked) {
            try {
                work.run()
            } catch (failed: AssertionError) {
                throw failed
            } catch (failure: Throwable) {
                var state = "none"
                var line = -1
                var cause: Throwable? = failure
                while (cause != null) {
                    if (cause is SQLException && cause.sqlState != null && cause.sqlState.matches(Regex("[0-9A-Z]{5}"))) {
                        state = cause.sqlState
                    }
                    for (frame in cause.stackTrace) {
                        if (frame.className == OwnedPostgresqlMainCoreIntegrationTest::class.java.name && line < 0) {
                            line = frame.lineNumber
                        }
                    }
                    cause = cause.cause
                }
                throw AssertionError(
                    "Owned P22 failed: " + phase + "; type=" + failure.javaClass.name + "; sqlstate=" + state + "; testLine=" + line,
                )
            }
        }

        private fun check(condition: Boolean, fixed: String) {
            if (!condition) {
                throw AssertionError(fixed)
            }
        }

        private fun expectClosed(work: Checked) {
            try {
                work.run()
            } catch (closed: IllegalStateException) {
                return
            } catch (failure: Exception) {
                throw AssertionError("Unexpected analyzer close result")
            }
            throw AssertionError("Actual analyzer must close")
        }

        private fun sha(value: ByteArray): String {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))
            } catch (failure: Exception) {
                throw AssertionError("SHA unavailable")
            }
        }
    }
}
