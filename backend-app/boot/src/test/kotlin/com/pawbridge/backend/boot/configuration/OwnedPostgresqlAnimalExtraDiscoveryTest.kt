package com.pawbridge.backend.boot.configuration

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.pawbridge.backend.boot.security.PostTokenAuthenticator
import com.pawbridge.backend.support.TestFaker
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.jsonwebtoken.security.Keys
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.InputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import javax.sql.DataSource

/** P10 reader reuse with at most six registered synthetic rows; no original seed or privilege changes. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class OwnedPostgresqlAnimalExtraDiscoveryTest {
    private var pool: HikariDataSource? = null
    private var applicationSource: ObservedSource? = null
    private var fixtureSource: ObservedSource? = null
    private lateinit var control: JdbcTemplate
    private lateinit var preparation: TransactionTemplate
    private lateinit var settings: Pair
    private lateinit var original: Baseline
    private lateinit var seeds: List<Seed>
    private val registered: MutableMap<Long, Seed> = LinkedHashMap()
    private lateinit var day: LocalDate
    private var shelterName: String? = null
    private val previousLogLevels: MutableMap<Logger, Level?> = LinkedHashMap()

    private fun pool(): HikariDataSource = pool!!

    private fun application(): ObservedSource = applicationSource!!

    private fun fixture(): ObservedSource = fixtureSource!!

    @BeforeAll
    fun requireExactP10PairAndOriginalBaselineBeforeRegisteringAnySyntheticRows() {
        observed("P10 paired identity") {
            for (name in listOf(
                "org.springframework.jdbc",
                "com.zaxxer.hikari",
                "org.springframework.web",
                "org.springframework.test.web.servlet",
                "org.springframework.security",
            )) {
                val logger = LoggerFactory.getLogger(name) as Logger
                previousLogLevels[logger] = logger.level
                logger.level = Level.OFF
            }
            settings = Pair.load()
            val fixtureDriver = DriverManagerDataSource(
                settings.writer.url,
                settings.writer.username,
                settings.writer.password,
            )
            val timeout = Properties()
            timeout.setProperty("connectTimeout", "3")
            timeout.setProperty("socketTimeout", "5")
            fixtureDriver.setConnectionProperties(timeout)
            fixtureSource = ObservedSource(fixtureDriver, false)
            control = JdbcTemplate(fixture())
            control.queryTimeout = 5
            verifyIdentity(control, settings.writer, false)
            val fixtureTables = control.query(
                "SELECT schemaname||'.'||tablename FROM pg_tables WHERE schemaname IN ('public','pawbridge_animal','pawbridge_user') ORDER BY schemaname,tablename",
                RowMapper { r, _ -> r.getString(1) },
            )
            check(
                fixtureTables.sorted() == TABLES.keys.sorted(),
                "Only the original eight application tables and marker are accepted before preparation",
            )
            for (table in TABLES.keys) {
                check(
                    true == control.queryForObject(
                        "SELECT has_table_privilege(current_user,?,'SELECT')",
                        Boolean::class.javaObjectType,
                        table,
                    ),
                    "The fixture role must already be able to observe the complete original baseline",
                )
            }
            check(
                true == control.queryForObject(
                    "SELECT has_table_privilege(current_user,'pawbridge_animal.animals','INSERT') AND has_table_privilege(current_user,'pawbridge_animal.animals','DELETE')",
                    Boolean::class.javaObjectType,
                ),
                "Existing fixture Animal insert/delete privileges are required without any grant",
            )
            for (sequence in SEQUENCES) {
                check(
                    true == control.queryForObject(
                        "SELECT has_sequence_privilege(current_user,?,'SELECT')",
                        Boolean::class.javaObjectType,
                        sequence,
                    ),
                    "Only already permitted sequence state may be observed",
                )
            }
            preparation = TransactionTemplate(JdbcTransactionManager(fixture()))
            preparation.timeout = 5
            val config = HikariConfig()
            config.jdbcUrl = settings.reader.url
            config.username = settings.reader.username
            config.password = settings.reader.password
            config.poolName = "owned-p10-extra-reader"
            config.maximumPoolSize = 1
            config.minimumIdle = 0
            config.isReadOnly = true
            config.connectionTimeout = 2000
            config.validationTimeout = 1000
            config.initializationFailTimeout = -1
            config.addDataSourceProperty("connectTimeout", "3")
            config.addDataSourceProperty("socketTimeout", "5")
            val reader = HikariDataSource(config)
            pool = reader
            val readerProbe = JdbcTemplate(reader)
            readerProbe.queryTimeout = 5
            verifyIdentity(readerProbe, settings.reader, true)
            applicationSource = ObservedSource(reader, true)
            check(
                control.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.animals WHERE id IN (990110,990111,990112,990113,990114,990115)",
                    Long::class.javaObjectType,
                ) == 6L,
                "All six original P10 filter rows must remain present",
            )
            check(
                control.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.animals WHERE id=990999",
                    Long::class.javaObjectType,
                ) == 0L,
                "The original P10 missing candidate must remain absent",
            )
            noResidue()
            shelterName = control.queryForObject(
                "SELECT name FROM pawbridge_animal.shelters WHERE id=?",
                String::class.java,
                SHELTER,
            )
            check(shelterName != null, "Only the existing P10 shelter may be referenced")
            original = baseline(false)
            val start = 8_000_000_000L + Math.floorMod(TestFaker.uuid().mostSignificantBits, 100_000_000L) * 10
            val selected = ArrayList<Seed>()
            for (index in 0 until 6) {
                val nonce = TestFaker.uuid().toString().replace("-", "")
                val id = start + (if (index == 0) 2 else if (index == 1) 1 else index + 1)
                selected.add(Seed(id, "EXTRA-" + nonce, "OWNED-EXTRA-" + nonce, PREFIX + nonce, index))
            }
            seeds = selected.toList()
            idle()
        }
    }

    @BeforeEach
    fun requireRestoredOriginalStateAndAStableJvmDate() {
        observed("extra before") {
            idle()
            check(registered.isEmpty(), "No registration may survive the preceding method")
            check(
                baseline(false) == original,
                "All eight original tables, marker and permitted sequence state must remain unchanged",
            )
            noResidue()
            day = LocalDate.now()
            check(
                control.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.animals WHERE status='PROTECT' AND notice_end_date BETWEEN ? AND ?",
                    Long::class.javaObjectType,
                    day,
                    day.plusDays(3),
                ) == 0L,
                "The unmodified original fixture must have no current expiring rows",
            )
            application().reset()
        }
    }

    @AfterEach
    fun removeOnlyRegisteredExactTriplesAndRestoreTheCompleteOriginalBaseline() {
        observed("extra exact cleanup") {
            idle()
            remove(ArrayList(registered.values))
            check(
                registered.isEmpty() && baseline(false) == original,
                "Only registered additions may disappear and every original table/sequence must return to baseline",
            )
            noResidue()
            idle()
        }
    }

    @AfterAll
    fun closeOnlyTheIdleReaderPool() {
        try {
            val reader = pool
            if (reader != null) {
                idle()
                reader.close()
                check(reader.isClosed, "The test-owned reader pool must close")
            }
        } finally {
            previousLogLevels.forEach { (logger, level) -> logger.level = level }
        }
    }

    @Test
    fun `채워진 소유 동물 하나가 주어졌을 때__APMS 번호로 조회하면__28개 필드와 보호소 조인을 돌려준다`() {
        observed("APMS actual reader") {
            val own = seeds.first()
            insert(listOf(own))
            val withOwn = baseline(false)
            http { mvc ->
                val actual = get(mvc, "/api/v1/animals/apms/" + own.apms, emptyMap(), 200, 1)
                val expected = JSON.readTree(JSON.writeValueAsBytes(details(own)))
                check(
                    actual.size() == 28 && actual == expected,
                    "Actual APMS lookup must preserve all28 non-null original detail fields and the existing shelter join value",
                )
                get(
                    mvc,
                    "/api/v1/animals/apms/MISSING-" + TestFaker.uuid().toString().replace("-", ""),
                    emptyMap(),
                    404,
                    1,
                )
            }
            check(baseline(false) == withOwn, "The APMS success and404 paths must not write even the added row")
            originalsUnchanged()
        }
    }

    @Test
    fun `경계 행 여섯이 주어졌을 때__곧 만료 목록을 페이지로 읽으면__고정 정렬과 포함 경계를 유지한다`() {
        observed("expiring actual reader") {
            insert(seeds)
            val withOwn = baseline(false)
            val expected = listOf(seeds[1].id, seeds[0].id, seeds[2].id)
            http { mvc ->
                page(get(mvc, "/api/v1/animals/expiring-soon", emptyMap(), 200, 2), expected, 0, 20, 3, 1, true, true)
                page(
                    get(mvc, "/api/v1/animals/expiring-soon", mapOf("sort" to "id,desc"), 200, 2),
                    expected,
                    0,
                    20,
                    3,
                    1,
                    true,
                    true,
                )
                page(
                    get(mvc, "/api/v1/animals/expiring-soon", mapOf("sort" to "unknown,desc"), 200, 2),
                    expected,
                    0,
                    20,
                    3,
                    1,
                    true,
                    true,
                )
                for (number in 0 until 3) {
                    page(
                        get(
                            mvc,
                            "/api/v1/animals/expiring-soon",
                            mapOf("page" to number.toString(), "size" to "1"),
                            200,
                            2,
                        ),
                        listOf(expected[number]),
                        number,
                        1,
                        3,
                        3,
                        number == 0,
                        number == 2,
                    )
                }
                page(
                    get(mvc, "/api/v1/animals/expiring-soon", mapOf("page" to "3", "size" to "1"), 200, 1),
                    emptyList(),
                    3,
                    1,
                    3,
                    3,
                    false,
                    true,
                )
            }
            check(
                baseline(false) == withOwn,
                "Every positive/offset query must leave all original and registered rows unchanged",
            )
            originalsUnchanged()
            remove(seeds.subList(0, 3))
            val withExcludedOnly = baseline(false)
            http { mvc ->
                page(get(mvc, "/api/v1/animals/expiring-soon", emptyMap(), 200, 1), emptyList(), 0, 20, 0, 0, true, true)
            }
            check(
                baseline(false) == withExcludedOnly,
                "The truly empty path must not change the remaining excluded or original rows",
            )
            originalsUnchanged()
        }
    }

    private fun http(action: HttpCheck) {
        WebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    PropertyPlaceholderAutoConfiguration::class.java,
                    JacksonAutoConfiguration::class.java,
                    HttpMessageConvertersAutoConfiguration::class.java,
                    ValidationAutoConfiguration::class.java,
                    SpringDataWebAutoConfiguration::class.java,
                    DispatcherServletAutoConfiguration::class.java,
                    WebMvcAutoConfiguration::class.java,
                    SecurityAutoConfiguration::class.java,
                    SecurityFilterAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(AnimalDiscoveryConfiguration::class.java, ReadSecurityConfiguration::class.java)
            .withBean("dataSource", DataSource::class.java, Supplier { application() })
            .withBean(
                "transactionManager",
                JdbcTransactionManager::class.java,
                Supplier { JdbcTransactionManager(application()) },
            )
            .withBean(
                PostTokenAuthenticator::class.java,
                Supplier {
                    PostTokenAuthenticator(
                        Keys.hmacShaKeyFor(
                            "owned-extra-discovery-public-synthetic-token-key-only-20261021".toByteArray(
                                StandardCharsets.UTF_8,
                            ),
                        ),
                    )
                },
            )
            .withPropertyValues(
                "pawbridge.animal-discovery.enabled=true",
                "pawbridge.animal-query.backend=postgresql",
                "spring.sql.init.mode=never",
                "spring.jmx.enabled=false",
            )
            .run { context ->
                check(context.startupFailure == null, "The actual discovery controller and Root security chain must start")
                val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context)
                    .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
                    .build()
                try {
                    action.run(mvc)
                } catch (failure: AssertionError) {
                    throw failure
                } catch (failure: Throwable) {
                    throw AssertionError("Owned extra discovery HTTP observation failed")
                }
            }
        check(!pool().isClosed, "Context closure must not close the external reader pool")
        idle()
    }

    private fun get(mvc: MockMvc, route: String, params: Map<String, String>, expected: Int, expectedSelects: Int): JsonNode {
        idle()
        sameDay()
        application().reset()
        val controlBefore = fixture().borrowed.get()
        val request: MockHttpServletRequestBuilder = MockMvcRequestBuilders.get(route)
        params.forEach { (name, value) -> request.param(name, value) }
        val response = mvc.perform(request).andReturn().response
        check(response.status == expected, "Unexpected owned extra discovery HTTP status")
        check(fixture().borrowed.get() == controlBefore, "The application request must never borrow the fixture account")
        check(
            application().borrowed.get() == 1 && application().samples.size == expectedSelects,
            "The actual reader must use one borrow and the expected business SELECT count",
        )
        check(
            application().samples.all { s ->
                s.readOnly && s.manualCommit && s.bound &&
                    s.actor == "pb_lost_search_reader" && s.isolation == "repeatable read" &&
                    s.timeout > 0 && s.timeout <= 5
            },
            "Actual PostgreSQL reader mode, actor, bounded statement timeout and binding must match the production RR contract",
        )
        check(
            application().samples.map { it.pid }.distinct().size == 1,
            "Page count/content must share the same physical snapshot connection",
        )
        idle()
        sameDay()
        return JSON.readTree(response.contentAsByteArray)
    }

    private fun insert(selected: List<Seed>) {
        idle()
        sameDay()
        check(
            registered.isEmpty() && selected.size <= 6,
            "Preparation may register at most six fresh rows after baseline restoration",
        )
        for (seed in selected) {
            check(
                control.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.animals WHERE id=? OR apms_desertion_no=? OR special_mark=?",
                    Long::class.javaObjectType,
                    seed.id,
                    seed.apms,
                    seed.mark,
                ) == 0L,
                "Every registered ID, APMS number and UUID marker must be absent before insertion",
            )
            registered[seed.id] = seed
        }
        preparation.executeWithoutResult {
            for (seed in selected) {
                val value = details(seed)
                val changed = control.update(
                    "INSERT INTO pawbridge_animal.animals(id,created_at,updated_at,api_source,apms_desertion_no,apms_notice_no,apms_process_state,apms_updated_at,birth_year,breed,color,description,favorite_count,gender,happen_date,happen_place,image_url,image_url2,neuter_status,notice_end_date,notice_start_date,special_mark,species,status,weight,shelter_id,search_revision,search_dirty) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,false)",
                    seed.id,
                    value["createdAt"],
                    value["updatedAt"],
                    "APMS_ANIMAL",
                    seed.apms,
                    seed.notice,
                    value["apmsProcessState"],
                    value["apmsUpdatedAt"],
                    2001,
                    value["breed"],
                    value["color"],
                    value["description"],
                    3,
                    "MALE",
                    value["happenDate"],
                    value["happenPlace"],
                    value["imageUrl"],
                    value["imageUrl2"],
                    "YES",
                    value["noticeEndDate"],
                    value["noticeStartDate"],
                    seed.mark,
                    "DOG",
                    value["status"],
                    "7.5",
                    SHELTER,
                )
                check(changed == 1, "Only the exact registered Animal insert may commit")
            }
        }
        idle()
        originalsUnchanged()
    }

    private fun remove(selected: List<Seed>) {
        idle()
        if (selected.isEmpty()) {
            return
        }
        val completed = ArrayList<Seed>()
        preparation.executeWithoutResult {
            for (seed in selected) {
                check(seed == registered[seed.id], "Cleanup may only inspect the current run's preregistered identity")
                val found = control.queryForList(
                    "SELECT id,apms_desertion_no,special_mark FROM pawbridge_animal.animals WHERE id=? OR apms_desertion_no=? OR special_mark=?",
                    seed.id,
                    seed.apms,
                    seed.mark,
                )
                check(found.size <= 1, "A registered identity must not resolve to ambiguous or foreign rows")
                if (!found.isEmpty()) {
                    val row = found.first()
                    check(
                        (row["id"] as Number).toLong() == seed.id && seed.apms == row["apms_desertion_no"] &&
                            seed.mark == row["special_mark"],
                        "ID, APMS number and UUID marker must all match before cleanup",
                    )
                    check(
                        control.queryForObject(
                            "SELECT count(*) FROM pawbridge_animal.chatbot_sessions WHERE animal_id=?",
                            Long::class.javaObjectType,
                            seed.id,
                        ) == 0L &&
                            control.queryForObject(
                                "SELECT count(*) FROM pawbridge_animal.animal_search_documents WHERE animal_id=?",
                                Long::class.javaObjectType,
                                seed.id,
                            ) == 0L &&
                            control.queryForObject(
                                "SELECT count(*) FROM pawbridge_animal.outbox_events WHERE aggregate_type='Animal' AND aggregate_id=?",
                                Long::class.javaObjectType,
                                seed.id.toString(),
                            ) == 0L,
                        "Unexpected dependent rows forbid cleanup instead of cascading or deleting them",
                    )
                    check(
                        control.update(
                            "DELETE FROM pawbridge_animal.animals WHERE id=? AND apms_desertion_no=? AND special_mark=?",
                            seed.id,
                            seed.apms,
                            seed.mark,
                        ) == 1,
                        "Cleanup must affect exactly its three-condition registered Animal",
                    )
                }
                completed.add(seed)
            }
        }
        // Keep registrations if commit acknowledgement is uncertain; do not silently adopt or wipe rows.
        completed.forEach { seed -> registered.remove(seed.id) }
        idle()
    }

    private fun details(seed: Seed): Map<String, Any?> {
        val end = when (seed.index) {
            2 -> day.plusDays(3)
            3 -> day.minusDays(1)
            4 -> day.plusDays(4)
            else -> day
        }
        val value = LinkedHashMap<String, Any?>()
        value["id"] = seed.id
        value["apmsNoticeNo"] = seed.notice
        value["species"] = "DOG"
        value["breed"] = "Owned breed"
        value["gender"] = "MALE"
        value["neuterStatus"] = "YES"
        value["birthYear"] = 2001
        value["age"] = day.year - 2001
        value["weight"] = "7.5"
        value["color"] = "brown"
        value["specialMark"] = seed.mark
        value["status"] = if (seed.index == 5) "NOTICE" else "PROTECT"
        value["noticeStartDate"] = day.minusDays(10)
        value["noticeEndDate"] = end
        value["happenPlace"] = "Owned place"
        value["happenDate"] = day.minusDays(2)
        value["imageUrl"] = "https://fixture.invalid/extra/" + seed.apms + "/one.jpg"
        value["imageUrl2"] = "https://fixture.invalid/extra/" + seed.apms + "/two.jpg"
        value["description"] = "Owned extra detail"
        value["favoriteCount"] = 3
        value["shelterId"] = SHELTER
        value["shelterName"] = shelterName
        value["apmsDesertionNo"] = seed.apms
        value["apmsProcessState"] = "Owned state"
        value["apmsUpdatedAt"] = LocalDateTime.of(day.minusDays(1), LocalTime.of(10, 11, 12))
        value["apiSource"] = "APMS_ANIMAL"
        value["createdAt"] = LocalDateTime.of(day.minusDays(10), LocalTime.of(1, 2, 3))
        value["updatedAt"] = LocalDateTime.of(day.minusDays(1), LocalTime.of(4, 5, 6))
        check(value.size == 28, "The owned detail oracle must contain exactly28 fields")
        return value
    }

    private fun baseline(excludeRegistered: Boolean): Baseline {
        val tables = LinkedHashMap<String, String>()
        TABLES.forEach { (table, key) ->
            var where = ""
            var args: Array<out Any?> = arrayOf()
            if (excludeRegistered && table == "pawbridge_animal.animals" && !registered.isEmpty()) {
                where = " WHERE id NOT IN (" + registered.keys.joinToString(",") { "?" } + ")"
                args = registered.keys.toTypedArray()
            }
            tables[table] = control.queryForObject(
                "SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(r)::text,',' ORDER BY " + key + "),'')) FROM " + table + " r" + where,
                String::class.java,
                *args,
            )!!
        }
        val sequences = LinkedHashMap<String, SequenceState>()
        for (sequence in SEQUENCES) {
            val row = control.queryForMap("SELECT last_value,is_called FROM " + sequence)
            sequences[sequence] = SequenceState((row["last_value"] as Number).toLong(), row["is_called"] as Boolean)
        }
        return Baseline(tables, sequences)
    }

    private fun originalsUnchanged() {
        check(
            baseline(true) == original,
            "Every original table row, marker and allowed sequence must remain unchanged while owned additions exist",
        )
    }

    private fun noResidue() {
        check(
            control.queryForObject(
                "SELECT count(*) FROM pawbridge_animal.animals WHERE special_mark LIKE ?",
                Long::class.javaObjectType,
                PREFIX + "%",
            ) == 0L,
            "Prior experimental residue forbids a new run and must never be prefix-deleted",
        )
    }

    private fun sameDay() {
        check(
            day == LocalDate.now(),
            "A JVM date change requires failure and exact cleanup, never a hidden clock policy or reseed",
        )
    }

    private fun idle() {
        val application = applicationSource
        val fixture = fixtureSource
        val reader = pool
        check(
            !TransactionSynchronizationManager.isActualTransactionActive() &&
                (application == null || !TransactionSynchronizationManager.hasResource(application)) &&
                (fixture == null || !TransactionSynchronizationManager.hasResource(fixture)),
            "No application or preparation transaction may escape its phase",
        )
        if (application != null) {
            application.requireReturned()
        }
        if (fixture != null) {
            fixture.requireReturned()
        }
        if (reader != null) {
            check(
                reader.hikariPoolMXBean.activeConnections == 0 && reader.hikariPoolMXBean.threadsAwaitingConnection == 0,
                "The single application reader must have no active or waiting borrower",
            )
        }
    }

    private class ObservedSource(target: DataSource, private val application: Boolean) : DelegatingDataSource(target) {
        val borrowed = AtomicInteger()
        val returned = AtomicInteger()
        val statements = AtomicInteger()
        val closedStatements = AtomicInteger()
        val results = AtomicInteger()
        val closedResults = AtomicInteger()
        val samples: MutableList<Sample> = ArrayList()

        override fun getConnection(): Connection {
            return wrap(super.getConnection())
        }

        override fun getConnection(username: String, password: String): Connection {
            return wrap(super.getConnection(username, password))
        }

        private fun wrap(connection: Connection): Connection {
            borrowed.incrementAndGet()
            val closed = AtomicBoolean()
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf<Class<*>>(Connection::class.java),
                InvocationHandler { _, method, args ->
                    if (method.name == "close") {
                        if (!closed.get()) {
                            call(connection, method, args)
                            closed.set(true)
                            returned.incrementAndGet()
                        }
                        return@InvocationHandler null
                    }
                    val value = call(connection, method, args)
                    if (application && method.name == "prepareStatement" && value is PreparedStatement && args[0] is String) {
                        return@InvocationHandler statement(connection, value, args[0] as String)
                    }
                    value
                },
            ) as Connection
        }

        private fun statement(connection: Connection, statement: PreparedStatement, sql: String): PreparedStatement {
            statements.incrementAndGet()
            val closed = AtomicBoolean()
            return Proxy.newProxyInstance(
                PreparedStatement::class.java.classLoader,
                arrayOf<Class<*>>(PreparedStatement::class.java),
                InvocationHandler { _, method, args ->
                    if (method.name == "close") {
                        if (!closed.get()) {
                            call(statement, method, args)
                            closed.set(true)
                            closedStatements.incrementAndGet()
                        }
                        return@InvocationHandler null
                    }
                    if (method.name == "executeQuery" && sql.contains("FROM pawbridge_animal.animals a JOIN pawbridge_animal.shelters s")) {
                        connection.createStatement().use { inspection ->
                            inspection.executeQuery(
                                "SELECT current_user,current_setting('transaction_read_only'),current_setting('transaction_isolation'),pg_backend_pid()",
                            ).use { row ->
                                check(row.next(), "Actual reader mode must be observable on its borrowed connection")
                                samples.add(
                                    Sample(
                                        row.getString(1),
                                        "on" == row.getString(2),
                                        row.getString(3),
                                        row.getInt(4),
                                        statement.queryTimeout,
                                        !connection.autoCommit,
                                        TransactionSynchronizationManager.isActualTransactionActive() &&
                                            TransactionSynchronizationManager.isCurrentTransactionReadOnly() &&
                                            TransactionSynchronizationManager.hasResource(this@ObservedSource),
                                    ),
                                )
                            }
                        }
                    }
                    val value = call(statement, method, args)
                    if (value is ResultSet) result(value) else value
                },
            ) as PreparedStatement
        }

        private fun result(result: ResultSet): ResultSet {
            results.incrementAndGet()
            val closed = AtomicBoolean()
            return Proxy.newProxyInstance(
                ResultSet::class.java.classLoader,
                arrayOf<Class<*>>(ResultSet::class.java),
                InvocationHandler { _, method, args ->
                    if (method.name == "close") {
                        if (!closed.get()) {
                            call(result, method, args)
                            closed.set(true)
                            closedResults.incrementAndGet()
                        }
                        return@InvocationHandler null
                    }
                    call(result, method, args)
                },
            ) as ResultSet
        }

        fun requireReturned() {
            check(
                borrowed.get() == returned.get() && statements.get() == closedStatements.get() &&
                    results.get() == closedResults.get(),
                "Every connection, prepared statement and result cursor must return after the selected phase",
            )
        }

        fun reset() {
            requireReturned()
            borrowed.set(0)
            returned.set(0)
            statements.set(0)
            closedStatements.set(0)
            results.set(0)
            closedResults.set(0)
            samples.clear()
        }
    }

    private fun interface Checked {
        fun run()
    }

    private fun interface HttpCheck {
        fun run(mvc: MockMvc)
    }

    private data class Seed(val id: Long, val apms: String, val notice: String, val mark: String, val index: Int)

    private data class SequenceState(val last: Long, val called: Boolean)

    private data class Baseline(val tables: Map<String, String>, val sequences: Map<String, SequenceState>)

    private data class Sample(
        val actor: String,
        val readOnly: Boolean,
        val isolation: String,
        val pid: Int,
        val timeout: Int,
        val manualCommit: Boolean,
        val bound: Boolean,
    )

    private data class Credential(
        val url: String,
        val database: String,
        val username: String,
        val password: String,
        val marker: String,
    ) {
        override fun toString(): String = "OwnedExtraCredential[redacted]"
    }

    private data class Pair(val reader: Credential, val writer: Credential) {
        override fun toString(): String = "OwnedExtraSettingsPair[redacted]"

        companion object {
            fun load(): Pair {
                val value = System.getenv("P10_PG_SETTINGS")
                check(value != null && value.isNotBlank(), "P10_PG_SETTINGS is mandatory without skip or P8 fallback")
                val readerPath = Path.of(value)
                val read = protectedProperties(readerPath, "lost-search.properties")
                val write = protectedProperties(
                    readerPath.resolveSibling("lost-search-fixture.properties"),
                    "lost-search-fixture.properties",
                )
                check(
                    read.stringPropertyNames() == setOf("jdbcUrl", "database", "username", "password", "marker"),
                    "The reader must retain its exact five settings",
                )
                check(
                    write.stringPropertyNames() ==
                        setOf("jdbcUrl", "database", "username", "password", "marker", "readerUsername", "readerPassword"),
                    "Only the existing seven fixture settings may be consumed",
                )
                val reader = credential(read)
                val writer = credential(write)
                check(
                    reader.database == DB && reader.url == "jdbc:postgresql://127.0.0.1:52620/" + DB &&
                        reader.username == "pb_lost_search_reader" && writer.username == "pb_lost_search_fixture" &&
                        writer.url == reader.url && writer.database == reader.database && writer.marker == reader.marker &&
                        required(write, "readerUsername") == reader.username &&
                        required(write, "readerPassword") == reader.password &&
                        reader.marker.matches(Regex("[0-9a-f]{32}")),
                    "Both protected settings must identify the same exact P10 target and existing reader credentials",
                )
                return Pair(reader, writer)
            }

            private fun credential(value: Properties): Credential {
                return Credential(
                    required(value, "jdbcUrl"),
                    required(value, "database"),
                    required(value, "username"),
                    required(value, "password"),
                    required(value, "marker"),
                )
            }

            private fun protectedProperties(path: Path, name: String): Properties {
                check(
                    path.isAbsolute && path.normalize() == path && path.fileName.toString() == name &&
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path),
                    "Only the exact absolute regular non-link P10 settings pair is accepted",
                )
                check(
                    Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) ==
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE) &&
                        (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1,
                    "Both settings require0600 and one hard link",
                )
                check(
                    System.getProperty("user.name") == Files.getOwner(path).name &&
                        Files.getOwner(path) == Files.getOwner(path.parent),
                    "Both settings and parent must retain the current owner",
                )
                var parent: Path? = path.parent
                while (parent != null) {
                    check(!Files.isSymbolicLink(parent), "Settings ancestors must not be symbolic links")
                    parent = parent.parent
                }
                val permissions = Files.getPosixFilePermissions(path.parent)
                check(
                    !permissions.contains(PosixFilePermission.GROUP_WRITE) &&
                        !permissions.contains(PosixFilePermission.OTHERS_WRITE),
                    "The existing P10 settings parent must reject other writers",
                )
                val result = Properties()
                Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input: InputStream -> result.load(input) }
                return result
            }

            private fun required(values: Properties, key: String): String {
                val value = values.getProperty(key)
                check(value != null && value.isNotBlank(), "Every approved setting is mandatory")
                return value
            }
        }
    }

    companion object {
        private const val DB = "pb_animal_command_owned_02c82195e3c7"
        private const val PREFIX = "OWNED-EXTRA-DISCOVERY-"
        private const val SHELTER = 990010L
        private val JSON: ObjectMapper = ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        private val TABLES: Map<String, String> = mapOf(
            "pawbridge_animal.animals" to "id",
            "pawbridge_animal.shelters" to "id",
            "pawbridge_animal.outbox_events" to "id",
            "pawbridge_animal.chatbot_sessions" to "id",
            "pawbridge_animal.animal_search_documents" to "animal_id",
            "pawbridge_animal.shelter_search_documents" to "shelter_id",
            "pawbridge_user.users" to "user_id",
            "pawbridge_user.contact_deletions" to "user_id",
            "public.pawbridge_owned_fixture" to "marker",
        )
        private val SEQUENCES: List<String> = listOf(
            "pawbridge_animal.animals_id_seq",
            "pawbridge_animal.outbox_events_id_seq",
        )

        private fun page(
            actual: JsonNode,
            expected: List<Long>,
            number: Int,
            size: Int,
            total: Long,
            totalPages: Int,
            first: Boolean,
            last: Boolean,
        ) {
            val fields = HashSet<String>()
            actual.fieldNames().forEachRemaining { fields.add(it) }
            check(
                fields == setOf("content", "number", "size", "totalElements", "totalPages", "first", "last"),
                "The original seven-field Page wire contract must remain exact",
            )
            val ids = ArrayList<Long>()
            actual.path("content").forEach { row -> ids.add(row.path("id").asLong()) }
            check(
                ids == expected && actual.path("number").asInt() == number && actual.path("size").asInt() == size &&
                    actual.path("totalElements").asLong() == total && actual.path("totalPages").asInt() == totalPages &&
                    actual.path("first").asBoolean() == first && actual.path("last").asBoolean() == last,
                "Inclusive boundaries, ID ties, fixed sorting, page offset and empty metadata must retain their actual contracts",
            )
        }

        private fun verifyIdentity(jdbc: JdbcTemplate, credential: Credential, reader: Boolean) {
            val id = jdbc.queryForMap(
                "SELECT current_database() db,current_user actor,session_user session,current_setting('server_version_num') version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) owner",
            )
            check(
                DB == id["db"] && credential.username == id["actor"] && credential.username == id["session"] &&
                    "170011" == id["version"] && credential.username != id["owner"],
                "Only the exact P10 database, actor and separate owner are permitted; P8 fallback is forbidden",
            )
            val marker = jdbc.query("SELECT marker FROM public.pawbridge_owned_fixture", RowMapper { r, _ -> r.getString(1) })
            check(
                marker.size == 1 && credential.marker == marker.first(),
                "Both accounts must observe the exact original single marker",
            )
            check(
                true == jdbc.queryForObject(
                    "SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit AND NOT pg_has_role(current_user,(SELECT datdba FROM pg_database WHERE datname=current_database()),'MEMBER') FROM pg_roles WHERE rolname=current_user",
                    Boolean::class.javaObjectType,
                ),
                "Neither account may gain owner or administrative inheritance",
            )
            for (schema in listOf("public", "pawbridge_animal", "pawbridge_user")) {
                check(
                    false == jdbc.queryForObject(
                        "SELECT has_schema_privilege(current_user,?,'CREATE')",
                        Boolean::class.javaObjectType,
                        schema,
                    ),
                    "Existing accounts must not create schema objects",
                )
            }
            check(
                false == jdbc.queryForObject(
                    "SELECT has_database_privilege(current_user,current_database(),'CREATE,TEMP')",
                    Boolean::class.javaObjectType,
                ),
                "Neither existing account may create database or temporary objects",
            )
            if (reader) {
                for (table in listOf("pawbridge_animal.animals", "pawbridge_animal.shelters")) {
                    check(
                        true == jdbc.queryForObject(
                            "SELECT has_table_privilege(current_user,?,'SELECT') AND NOT has_table_privilege(current_user,?,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT has_any_column_privilege(current_user,?,'INSERT,UPDATE,REFERENCES')",
                            Boolean::class.javaObjectType,
                            table,
                            table,
                            table,
                        ),
                        "The actual application reader must remain SELECT-only",
                    )
                }
                check(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind IN('r','p','v','m','f') AND (has_table_privilege(current_user,c.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') OR has_any_column_privilege(current_user,c.oid,'INSERT,UPDATE,REFERENCES') OR ((has_table_privilege(current_user,c.oid,'SELECT') OR has_any_column_privilege(current_user,c.oid,'SELECT')) AND (n.nspname||'.'||c.relname) NOT IN ('pawbridge_animal.animals','pawbridge_animal.shelters','public.pawbridge_owned_fixture')))",
                        Long::class.javaObjectType,
                    ) == 0L,
                    "The reader must retain no writes or unrelated reads",
                )
                check(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind='S' AND has_sequence_privilege(current_user,c.oid,'USAGE,SELECT,UPDATE')",
                        Long::class.javaObjectType,
                    ) == 0L,
                    "The reader must retain no sequence privilege",
                )
                check(
                    "on" == jdbc.queryForObject("SHOW transaction_read_only", String::class.java),
                    "The existing reader must continue to default to read-only",
                )
            }
        }

        private fun call(target: Any, method: Method, args: Array<Any?>?): Any? {
            try {
                return method.invoke(target, *(args ?: arrayOf()))
            } catch (failure: InvocationTargetException) {
                throw failure.cause!!
            }
        }

        private fun observed(phase: String, action: Checked) {
            try {
                action.run()
            } catch (failure: AssertionError) {
                throw failure
            } catch (failure: Throwable) {
                throw AssertionError("Owned extra discovery failed: " + phase + "; type=" + failure.javaClass.name)
            }
        }

        private fun check(value: Boolean, fixed: String) {
            if (!value) {
                throw AssertionError(fixed)
            }
        }
    }
}
