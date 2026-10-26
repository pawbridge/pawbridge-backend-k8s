package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.search.PostgresqlSearchProjector
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.application.SearchProjectionPendingException
import com.pawbridge.backend.animal.internal.discovery.infrastructure.JdbcAnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
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
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
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
import java.sql.SQLException
import java.util.HexFormat
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exact new P20 only; one ordered class, no DDL/seed/reset, real existing search and analyzer. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(60)
class OwnedPostgresqlAnimalSearchProjectionTest {
    private val levels = LinkedHashMap<ch.qos.logback.classic.Logger, ch.qos.logback.classic.Level?>()
    private lateinit var writer: Settings
    private lateinit var failure: Settings
    private var pool: ObservedPool? = null
    private var failurePool: ObservedPool? = null
    private lateinit var jdbc: JdbcTemplate
    private lateinit var failureJdbc: JdbcTemplate
    private lateinit var manager: JdbcTransactionManager
    private lateinit var writes: TransactionTemplate
    private var analyzer: KoreanSearchAnalyzer? = null
    private lateinit var projector: PostgresqlSearchProjector
    private lateinit var deniedProjector: PostgresqlSearchProjector
    private lateinit var discovery: JdbcAnimalDiscovery
    private lateinit var direct: DriverManagerDataSource
    private lateinit var immutableSource: String
    private lateinit var outbox: String
    private var nextAllowed = false
    private var bodyCompleted = false
    private var unsettled = false
    private var nextOrder = 1

    @BeforeAll
    fun givenExactFreshP20__whenPrepared__thenGuardIdentityAclSeedAndExistingContractsBeforeMaintenance() {
        observed("P20 preparation") {
            quiet()
            writer = Settings.load(false)
            failure = Settings.load(true)
            check(
                writer.url == failure.url && writer.database == failure.database && writer.marker == failure.marker,
                "Both minimal roles must belong to the same new P20 target and marker",
            )
            javaClass.getResourceAsStream("/owned-postgresql/animal-search-projection-owned-schema.sql").use { source ->
                check(source != null && sha(source.readAllBytes()) == FIXTURE_SHA, "Exact frozen P20 SQL binding is mandatory")
            }
            val writerPool = pool(writer, 2, "owned-p20-projection")
            val deniedPool = pool(failure, 1, "owned-p20-projection-denied")
            pool = writerPool
            failurePool = deniedPool
            jdbc = JdbcTemplate(writerPool)
            jdbc.queryTimeout = 5
            failureJdbc = JdbcTemplate(deniedPool)
            failureJdbc.queryTimeout = 5
            verifyTarget(jdbc, writer, false)
            verifyTarget(failureJdbc, failure, true)
            check(
                count("animals") == 6L && count("shelters") == 6L && count("outbox_events") == 1L &&
                    count("animal_search_documents") == 4L && count("shelter_search_documents") == 4L,
                "P20 begins only from its exact initial source, incomplete documents and sentinel",
            )
            check(
                jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.animals WHERE search_dirty", Long::class.javaObjectType) == 1L &&
                    jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.shelters WHERE search_dirty", Long::class.javaObjectType) == 1L,
                "Only the two initial dirty rows may exist before the first contract",
            )
            immutableSource = sourceFingerprint()
            outbox = fingerprint("outbox_events")
            manager = JdbcTransactionManager(writerPool)
            writes = TransactionTemplate(manager)
            writes.timeout = 5
            val koreanAnalyzer = KoreanSearchAnalyzer()
            analyzer = koreanAnalyzer
            projector = PostgresqlSearchProjector(jdbc, writes, koreanAnalyzer)
            val deniedWrites = TransactionTemplate(JdbcTransactionManager(deniedPool))
            deniedWrites.timeout = 5
            deniedProjector = PostgresqlSearchProjector(failureJdbc, deniedWrites, koreanAnalyzer)
            discovery = JdbcAnimalDiscovery(writerPool, manager, koreanAnalyzer)
            direct = DriverManagerDataSource(writer.url, writer.username, writer.password)
            val timeouts = Properties()
            timeouts.setProperty("connectTimeout", "3")
            timeouts.setProperty("socketTimeout", "5")
            direct.setConnectionProperties(timeouts)
            released()
            nextAllowed = true
        }
    }

    @BeforeEach
    fun givenPriorFullContract__whenNextStarts__thenFencePartialSelectionAndEarlierFailure(info: TestInfo) {
        val prior = nextAllowed
        nextAllowed = false
        bodyCompleted = false
        observed("P20 prior contract") {
            check(
                prior && !unsettled && info.testMethod.orElseThrow().getAnnotation(Order::class.java).value == nextOrder,
                "Only the full ordered P20 class may proceed after previous success",
            )
            released()
        }
    }

    @AfterEach
    fun givenCaseEnded__whenAudited__thenReleaseResourcesAndPreserveSourceAndOutbox() {
        nextAllowed = false
        observed("P20 case cleanup") {
            released()
            check(
                immutableSource == sourceFingerprint() && outbox == fingerprint("outbox_events"),
                "Maintenance may change dirty flags/documents but no source content, revision, URL, status or Outbox sentinel",
            )
            check(
                writer.marker == jdbc.queryForObject("SELECT marker FROM public.pb_animal_projection_fixture", String::class.java),
                "Marker remains unchanged",
            )
            released()
            check(bodyCompleted, "A failed body fences every later P20 write")
            nextOrder++
            nextAllowed = true
        }
    }

    @AfterAll
    fun givenSettledClass__whenClosing__thenCloseOwnedResourcesAndRequireAllFiveContracts() {
        try {
            observed("P20 final cleanup") {
                check(!unsettled, "Unknown work settlement forbids false cleanup")
                released()
                analyzer?.close()
                val deniedPool = failurePool
                if (deniedPool != null) {
                    deniedPool.close()
                    check(deniedPool.isClosed, "Failure-role pool must close")
                }
                val writerPool = pool
                if (writerPool != null) {
                    writerPool.close()
                    check(writerPool.isClosed, "Writer pool must close")
                }
                println(
                    "ANIMAL_PROJECTION_OWNED completedContracts=" + (nextOrder - 1) +
                        " fullClass=" + (nextOrder == 6 && nextAllowed) + " historyRetained=true",
                )
                check(nextOrder == 6 && nextAllowed, "Exactly five complete non-skipped contracts are required; prefix selection is not success")
            }
        } finally {
            if (!unsettled) {
                levels.forEach { (logger, level) -> logger.level = level }
            }
        }
    }

    @Test
    @Order(1)
    fun `더티 동물과 보호소가 주어졌을 때__두 유형을 복구하면__기존 키워드 검색이 복구된다`() {
        contract("dirty search recovery") {
            expect(SearchProjectionPendingException::class.java) { search(1, "말티즈") }
            check(projector.refreshDirtyShelters(1) == 1, "The first bounded Shelter page repairs its dirty source")
            expect(SearchProjectionPendingException::class.java) { search(1, "말티즈") }
            check(projector.refreshDirtyAnimals(1) == 1, "Animal repair remains a separate bounded transaction")
            requireSearch(1, "말티즈")
            requireCurrent("animals", 1)
            requireCurrent("shelters", 1)
        }
    }

    @Test
    @Order(2)
    fun `리비전 누락과 분석기 불일치가 주어졌을 때__페이지당 한 행씩 감사하면__모든 종류를 복구한다`() {
        contract("bounded clean audit") {
            check(
                projector.refreshDirtyAnimals(1) == 0 && projector.refreshDirtyShelters(1) == 0,
                "A dirty-only zero does not repair clean missing or inconsistent documents",
            )
            check(
                search(2, "진돗개").content().isEmpty(),
                "The existing clean-missing path may return no match rather than dirty503",
            )
            check(
                projector.refreshShelters(1) == 1 && projector.refreshAnimals(1) == 1,
                "The first audit page repairs only the missing row per type",
            )
            requireCurrent("animals", 2)
            requireCurrent("shelters", 2)
            requireSearch(2, "진돗개")
            check(
                jdbc.queryForObject(
                    "SELECT source_revision FROM pawbridge_animal.animal_search_documents WHERE animal_id=3",
                    Long::class.javaObjectType,
                ) == 0L &&
                    "p20-legacy" == jdbc.queryForObject(
                        "SELECT analyzer_version FROM pawbridge_animal.animal_search_documents WHERE animal_id=4",
                        String::class.java,
                    ) &&
                    jdbc.queryForObject(
                        "SELECT source_revision FROM pawbridge_animal.shelter_search_documents WHERE shelter_id=3",
                        Long::class.javaObjectType,
                    ) == 0L &&
                    "p20-legacy" == jdbc.queryForObject(
                        "SELECT analyzer_version FROM pawbridge_animal.shelter_search_documents WHERE shelter_id=4",
                        String::class.java,
                    ),
                "One page cannot silently fix later revision or analyzer mismatches",
            )
            for (id in listOf(3, 4)) {
                check(
                    projector.refreshShelters(1) == 1 && projector.refreshAnimals(1) == 1,
                    "Each later audit is another bounded one-row page",
                )
                requireCurrent("animals", id)
                requireCurrent("shelters", id)
            }
            check(
                projector.refreshAnimals(1) == 0 && projector.refreshShelters(1) == 0,
                "The current unlocked fixture has no further audit rows; this is not an operational corpus-complete claim",
            )
        }
    }

    @Test
    @Order(3)
    fun `다른 세션이 더티 원본을 잠갔을 때__SKIP LOCKED가 0을 돌려주면__해제 뒤 재시도까지 대기로 남는다`() {
        contract("physical source locks") {
            for (table in listOf("animals", "shelters")) {
                jdbc.update("UPDATE pawbridge_animal." + table + " SET search_dirty=true WHERE id=5")
                direct.connection.use { holder ->
                    check(
                        pid(holder) != jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.javaObjectType),
                        "The lock holder and projector must have distinct real PostgreSQL PIDs",
                    )
                    holder.autoCommit = false
                    holder.prepareStatement("SELECT id FROM pawbridge_animal." + table + " WHERE id=5 FOR UPDATE").use { lock ->
                        try {
                            lock.queryTimeout = 3
                            lock.executeQuery().use { row -> check(row.next(), "The exact owned source row must be locked") }
                            val repaired = if (table == "animals") projector.refreshDirtyAnimals(1) else projector.refreshDirtyShelters(1)
                            check(
                                repaired == 0 && true == jdbc.queryForObject(
                                    "SELECT search_dirty FROM pawbridge_animal." + table + " WHERE id=5",
                                    Boolean::class.javaObjectType,
                                ),
                                "SKIP LOCKED zero leaves a known pending source and is not completion",
                            )
                            expect(SearchProjectionPendingException::class.java) { search(5, "비글") }
                            released()
                        } finally {
                            holder.rollback()
                        }
                    }
                }
                check(
                    (if (table == "animals") projector.refreshDirtyAnimals(1) else projector.refreshDirtyShelters(1)) == 1,
                    "Unlock permits the next bounded retry",
                )
                requireCurrent(table, 5)
                requireSearch(5, "비글")
            }
        }
    }

    @Test
    @Order(4)
    fun `문서 쓰기 권한이 없는 역할이 주어졌을 때__복구가 실패하면__롤백하고 쓰기 역할 재시도로 복구된다`() {
        contract("permission rollback and recovery") {
            for (table in listOf("shelters", "animals")) {
                jdbc.update("UPDATE pawbridge_animal." + table + " SET search_dirty=true WHERE id=6")
                val before = allFingerprint()
                val rollback = failurePool!!.rollbacks.get()
                try {
                    if (table == "animals") {
                        deniedProjector.refreshDirtyAnimals(1)
                    } else {
                        deniedProjector.refreshDirtyShelters(1)
                    }
                    throw AssertionError("Document permission denial must be reached")
                } catch (denied: DataAccessException) {
                    check("42501" == sqlState(denied), "Only actual PostgreSQL insufficient-privilege is the expected failure")
                }
                check(
                    failurePool!!.rollbacks.get() > rollback && before == allFingerprint() &&
                        true == jdbc.queryForObject(
                            "SELECT search_dirty FROM pawbridge_animal." + table + " WHERE id=6",
                            Boolean::class.javaObjectType,
                        ),
                    "The failed real page must roll back, preserve all documents and leave dirty for retry",
                )
                check(
                    (if (table == "animals") projector.refreshDirtyAnimals(1) else projector.refreshDirtyShelters(1)) == 1,
                    "The original writer can recover the failed page without privilege changes",
                )
                requireCurrent(table, 6)
            }
            requireSearch(6, "치와와")
        }
    }

    @Test
    @Order(5)
    fun `실제 바깥 트랜잭션이 주어졌을 때__새로고침을 호출하면__SQL 전에 거절하고 모든 테이블을 보존한다`() {
        contract("outer transaction refusal") {
            val before = allFingerprint()
            writes.executeWithoutResult {
                check(TransactionSynchronizationManager.isActualTransactionActive(), "The test must enter an actual writer transaction")
                val statements = pool!!.statements.get()
                expect(IllegalStateException::class.java) { projector.refreshDirtyAnimals(1) }
                expect(IllegalStateException::class.java) { projector.refreshDirtyShelters(1) }
                expect(IllegalStateException::class.java) { projector.refreshAnimals(1) }
                expect(IllegalStateException::class.java) { projector.refreshShelters(1) }
                check(pool!!.statements.get() == statements, "Every actual outer-TX refusal must happen before SQL")
            }
            check(before == allFingerprint(), "Outer-TX refusal must preserve source, documents and Outbox")
        }
    }

    private fun search(id: Int, keyword: String): AnimalDiscovery.Page {
        val request = AnimalDiscovery.Search(
            null, null, null, null, null, null, null, null, null, keyword, "p20-" + id, null, null, null,
        )
        return discovery.search(
            request,
            AnimalDiscovery.PageRequest(0, 10, listOf(AnimalDiscovery.Order("createdAt", false)), true),
        )
    }

    private fun requireSearch(id: Int, keyword: String) {
        val result = search(id, keyword)
        check(
            result.totalElements() == 1L && result.content().map { summary -> summary.id } == listOf(id.toLong()),
            "The actual existing keyword search must return exactly the repaired Animal",
        )
    }

    private fun requireCurrent(table: String, id: Int) {
        val doc = if (table == "animals") "animal_search_documents" else "shelter_search_documents"
        val fk = if (table == "animals") "animal_id" else "shelter_id"
        check(
            true == jdbc.queryForObject(
                "SELECT NOT s.search_dirty AND d.source_revision=s.search_revision AND d.analyzer_version=? FROM pawbridge_animal." + table + " s JOIN pawbridge_animal." + doc + " d ON d." + fk + "=s.id WHERE s.id=?",
                Boolean::class.javaObjectType,
                KoreanSearchAnalyzer.VERSION,
                id,
            ),
            "Source dirty, revision and analyzer metadata must be current",
        )
    }

    private fun count(table: String): Long {
        check(KEYS.containsKey(table), "Only fixed P20 tables are counted")
        return jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal." + table, Long::class.javaObjectType)!!
    }

    private fun fingerprint(table: String): String {
        check(KEYS.containsKey(table), "Only fixed P20 tables are fingerprinted")
        return jdbc.queryForObject(
            "SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY " + KEYS[table] + "),'')) FROM pawbridge_animal." + table + " t",
            String::class.java,
        )!!
    }

    private fun allFingerprint(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        KEYS.keys.sorted().forEach { table -> result[table] = fingerprint(table) }
        return result
    }

    private fun sourceFingerprint(): String {
        val result = StringBuilder()
        for (table in listOf("animals", "shelters")) {
            result.append(
                jdbc.queryForObject(
                    "SELECT count(*)::text||':'||md5(COALESCE(string_agg((to_jsonb(t)-'search_dirty')::text,',' ORDER BY id),'')) FROM pawbridge_animal." + table + " t",
                    String::class.java,
                ),
            ).append('|')
        }
        return result.toString()
    }

    private fun released() {
        check(!unsettled, "Unsettled resources fence later access")
        val writerPool = pool
        val deniedPool = failurePool
        val ok = !TransactionSynchronizationManager.isActualTransactionActive() &&
            (writerPool == null || returned(writerPool)) && (deniedPool == null || returned(deniedPool))
        if (!ok) {
            unsettled = true
        }
        check(ok, "All owned JDBC borrows and transaction resources must be returned")
    }

    private fun contract(phase: String, body: Checked) {
        observed(phase) {
            body.run()
            bodyCompleted = true
        }
    }

    private fun quiet() {
        for (name in listOf(
            "com.zaxxer.hikari",
            "org.springframework.jdbc",
            "org.springframework.transaction",
            "com.pawbridge.backend.animal.internal.command.infrastructure.search",
        )) {
            val logger = LoggerFactory.getLogger(name) as ch.qos.logback.classic.Logger
            levels[logger] = logger.level
            logger.level = ch.qos.logback.classic.Level.OFF
        }
    }

    private fun verifyTarget(db: JdbcTemplate, value: Settings, denied: Boolean) {
        val id = db.queryForMap(
            "SELECT current_database() db,current_user actor,session_user session,current_setting('server_version_num') version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) owner",
        )
        check(
            value.database == id["db"] && value.username == id["actor"] && value.username == id["session"] &&
                value.owner() == id["owner"] && "170011" == id["version"],
            "Only the new exact P20 PostgreSQL17.11 identity is accepted",
        )
        check(
            value.marker == db.queryForObject("SELECT marker FROM public.pb_animal_projection_fixture", String::class.java),
            "The exact P20 marker is mandatory",
        )
        check(
            true == db.queryForObject(
                "SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=pg_roles.oid) FROM pg_roles WHERE rolname=current_user",
                Boolean::class.javaObjectType,
            ),
            "Both restricted logins must lack ownership/admin membership",
        )
        check(
            true == db.queryForObject(
                "SELECT NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit FROM pg_roles WHERE rolname=?",
                Boolean::class.javaObjectType,
                value.owner(),
            ) &&
                false == db.queryForObject(
                    "SELECT pg_has_role(current_user,?,'MEMBER')",
                    Boolean::class.javaObjectType,
                    value.owner(),
                ),
            "Separate fixture owner remains NOLOGIN and inaccessible",
        )
        check(
            false == db.queryForObject(
                "SELECT has_database_privilege(current_user,current_database(),'CREATE,TEMP')",
                Boolean::class.javaObjectType,
            ),
            "Runtime database/TEMP DDL is forbidden",
        )
        for (schema in listOf("public", "pawbridge_animal")) {
            check(
                true == db.queryForObject(
                    "SELECT has_schema_privilege(current_user,?,'USAGE') AND NOT has_schema_privilege(current_user,?,'CREATE')",
                    Boolean::class.javaObjectType,
                    schema,
                    schema,
                ),
                "No runtime schema creation is allowed",
            )
        }
        check(
            db.query(
                "SELECT tablename FROM pg_tables WHERE schemaname='pawbridge_animal' ORDER BY tablename",
                RowMapper { row, _ -> row.getString(1) },
            ) == KEYS.keys.sorted(),
            "Only exact source/docs4 and Outbox sentinel tables exist",
        )
        check(
            db.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind IN('r','p','v','m','f')",
                Long::class.javaObjectType,
            ) == 6L,
            "Only five application tables and one marker are accepted",
        )
        for (table in KEYS.keys) {
            for (right in listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                val allowed = right == "SELECT" || (!denied && DOCS.contains(table) && setOf("INSERT", "UPDATE").contains(right))
                check(
                    allowed == db.queryForObject(
                        "SELECT has_table_privilege(current_user,?,?)",
                        Boolean::class.javaObjectType,
                        "pawbridge_animal." + table,
                        right,
                    ),
                    "Every table ACL must match the exact role boundary",
                )
                if (!allowed && setOf("INSERT", "UPDATE", "REFERENCES").contains(right) &&
                    !(SOURCE.contains(table) && right == "UPDATE")
                ) {
                    check(
                        false == db.queryForObject(
                            "SELECT has_any_column_privilege(current_user,?,?)",
                            Boolean::class.javaObjectType,
                            "pawbridge_animal." + table,
                            right,
                        ),
                        "Column privileges cannot bypass denied operations",
                    )
                }
            }
        }
        for (table in SOURCE) {
            val columns = db.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='pawbridge_animal' AND table_name=? ORDER BY ordinal_position",
                String::class.java,
                table,
            )
            check(columns.contains("search_dirty"), "Source dirty column must exist")
            for (column in columns) {
                check(
                    (column == "search_dirty") == db.queryForObject(
                        "SELECT has_column_privilege(current_user,?,?,'UPDATE')",
                        Boolean::class.javaObjectType,
                        "pawbridge_animal." + table,
                        column,
                    ),
                    "Only the exact source dirty column may be updated",
                )
            }
        }
        check(
            true == db.queryForObject(
                "SELECT has_table_privilege(current_user,'public.pb_animal_projection_fixture','SELECT') AND NOT has_table_privilege(current_user,'public.pb_animal_projection_fixture','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')",
                Boolean::class.javaObjectType,
            ),
            "Marker is SELECT-only",
        )
        check(
            db.query(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='pawbridge_animal' AND c.relkind='S' ORDER BY c.relname",
                RowMapper { row, _ -> row.getString(1) },
            ) == listOf("animals_id_seq", "outbox_events_id_seq", "shelters_id_seq"),
            "Only the three original source/sentinel identities exist",
        )
        for (seq in listOf("animals_id_seq", "shelters_id_seq", "outbox_events_id_seq")) {
            check(
                false == db.queryForObject(
                    "SELECT has_sequence_privilege(current_user,?,'USAGE,SELECT,UPDATE')",
                    Boolean::class.javaObjectType,
                    "pawbridge_animal." + seq,
                ),
                "No runtime source/sentinel identity writes are allowed",
            )
        }
        check(
            db.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN('public','pawbridge_animal') AND c.relkind IN('r','S') AND pg_get_userbyid(c.relowner)<>?",
                Long::class.javaObjectType,
                value.owner(),
            ) == 0L,
            "All fixture objects retain the separate owner",
        )
    }

    private fun interface Checked {
        fun run()
    }

    /** Narrow P18-style connection ownership counters; no SQL selector, statement logger or executor. */
    private class ObservedPool(config: HikariConfig) : HikariDataSource(config) {
        val borrows = AtomicInteger()
        val statements = AtomicInteger()
        val rollbacks = AtomicInteger()

        override fun getConnection(): Connection {
            val target = super.getConnection()
            borrows.incrementAndGet()
            val closed = AtomicBoolean()
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
                    if (method.name == "prepareStatement" || method.name == "createStatement") {
                        statements.incrementAndGet()
                    }
                    val value = call(target, method, args)
                    if (method.name == "rollback") {
                        rollbacks.incrementAndGet()
                    }
                    value
                },
            ) as Connection
        }
    }

    private class Settings(
        val url: String,
        val database: String,
        val username: String,
        val password: String,
        val marker: String,
    ) {
        fun owner(): String = database.replace("pb_animal_projection_owned_", "pb_animal_projection_owner_")

        override fun toString(): String = "OwnedProjectionSettings[redacted]"

        companion object {
            fun load(denied: Boolean): Settings {
                check("P20_PG_SETTINGS" == System.getenv("PAWBRIDGE_OWNED_SETTINGS_ENV"), "Only the exact P20 selector is accepted")
                val configured = System.getenv("P20_PG_SETTINGS")
                check(configured != null && configured.isNotBlank(), "P20 settings are required without fallback or skip")
                val primary = Path.of("/private/tmp/pawbridge-owned-pg-night-20261020/animal-search-projection/animal-search-projection.properties")
                check(Path.of(configured) == primary, "Only the exact exclusive P20 claim settings are accepted")
                val file = if (denied) primary.resolveSibling("failure.properties") else primary
                check(
                    file.isAbsolute && file.normalize() == file && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(file),
                    "P20 settings must be the exact private regular file",
                )
                var parent: Path? = file.parent
                while (parent != null) {
                    check(!Files.isSymbolicLink(parent), "Settings ancestors cannot be symlinks")
                    parent = parent.parent
                }
                check(
                    Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS) ==
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE) &&
                        (Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1 &&
                        System.getProperty("user.name") == Files.getOwner(file).name &&
                        Files.getOwner(file) == Files.getOwner(file.parent),
                    "P20 settings must be current-owner single-link0600",
                )
                check(
                    Files.getPosixFilePermissions(file.parent) ==
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                    "P20 claim directory must remain0700",
                )
                val values = Properties()
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input: InputStream -> values.load(input) }
                check(
                    values.stringPropertyNames() == setOf("jdbcUrl", "database", "username", "password", "marker"),
                    "Exactly the existing five credential fields are accepted",
                )
                val db = required(values, "database")
                val url = required(values, "jdbcUrl")
                val user = required(values, "username")
                val marker = required(values, "marker")
                check(
                    db.matches(Regex("pb_animal_projection_owned_[0-9a-f]{12}")) &&
                        url == "jdbc:postgresql://127.0.0.1:65309/" + db &&
                        user == db.replace(
                            "pb_animal_projection_owned_",
                            if (denied) "pb_animal_projection_failure_" else "pb_animal_projection_writer_",
                        ) &&
                        marker.matches(Regex("[0-9a-f]{32}")),
                    "Only the fresh exact P20 database, role, marker and resumed loopback port are accepted",
                )
                val state = file.resolveSibling("state.json")
                check(
                    Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(state) &&
                        Files.getPosixFilePermissions(state) == setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE) &&
                        (Files.getAttribute(state, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1 &&
                        Files.getOwner(state) == Files.getOwner(file),
                    "Exact P20 claim state must remain private",
                )
                val claim = ObjectMapper().readTree(Files.readAllBytes(state))
                check(
                    db == claim.path("database").asText() &&
                        user == claim.path(if (denied) "failure" else "writer").asText() &&
                        marker == claim.path("marker").asText() &&
                        "animal-search-projection-v1" == claim.path("purpose").asText() &&
                        FIXTURE_SHA == claim.path("schema_sha256").asText() &&
                        claim.path("schemaInitialized").asBoolean() && claim.path("port").asInt() == 65309 &&
                        "b1719e47b8c3ff8c334e9df96c86b4f36478eb72022b8e19278d75c9f8a7187c" == claim.path("containerId").asText() &&
                        "sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f" == claim.path("image").asText() &&
                        "08b97b84-e0b5-4c6f-a5f8-3d51ec4a2f89" == claim.path("owner").asText(),
                    "Claim must bind the exact P20 nonce, purpose, SQL and approved container identity",
                )
                return Settings(url, db, user, required(values, "password"), marker)
            }

            private fun required(values: Properties, key: String): String {
                val value = values.getProperty(key)
                check(value != null && value.isNotBlank(), "Every private P20 value is mandatory")
                return value
            }
        }
    }

    companion object {
        private const val FIXTURE_SHA = "be0bd49960e0dd85cf0ef1f25dc77704b5d61563bad9a5c989db7e4930875eae"
        private val KEYS: Map<String, String> = mapOf(
            "animals" to "id",
            "shelters" to "id",
            "outbox_events" to "id",
            "animal_search_documents" to "animal_id",
            "shelter_search_documents" to "shelter_id",
        )
        private val SOURCE: Set<String> = setOf("animals", "shelters")
        private val DOCS: Set<String> = setOf("animal_search_documents", "shelter_search_documents")

        private fun returned(source: ObservedPool): Boolean {
            return !TransactionSynchronizationManager.hasResource(source) && source.borrows.get() == 0 &&
                source.hikariPoolMXBean.activeConnections == 0 && source.hikariPoolMXBean.threadsAwaitingConnection == 0
        }

        private fun pid(source: Connection): Int {
            source.createStatement().use { statement ->
                statement.queryTimeout = 3
                statement.executeQuery("SELECT pg_backend_pid()").use { row ->
                    check(row.next(), "A real backend PID is required")
                    return row.getInt(1)
                }
            }
        }

        private fun sqlState(source: Throwable): String? {
            var current: Throwable? = source
            var i = 0
            while (current != null && i < 10) {
                if (current is SQLException) {
                    return current.sqlState
                }
                i++
                current = current.cause
            }
            return null
        }

        private fun sha(bytes: ByteArray): String {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            } catch (impossible: Exception) {
                throw AssertionError("SHA unavailable")
            }
        }

        private fun observed(phase: String, body: Checked) {
            try {
                body.run()
            } catch (failure: AssertionError) {
                throw failure
            } catch (failure: Throwable) {
                throw AssertionError("Owned P20 failed: " + phase + "; type=" + failure.javaClass.name)
            }
        }

        private fun expect(type: Class<out Throwable>, body: Checked) {
            try {
                body.run()
            } catch (failure: Throwable) {
                check(type.isInstance(failure), "Only the exact expected refusal category is accepted")
                return
            }
            throw AssertionError("Expected refusal is absent")
        }

        private fun check(ok: Boolean, fixed: String) {
            if (!ok) {
                throw AssertionError(fixed)
            }
        }

        private fun pool(value: Settings, capacity: Int, name: String): ObservedPool {
            val c = HikariConfig()
            c.jdbcUrl = value.url
            c.username = value.username
            c.password = value.password
            c.poolName = name
            c.maximumPoolSize = capacity
            c.minimumIdle = 0
            c.initializationFailTimeout = -1
            c.connectionTimeout = 2000
            c.validationTimeout = 1000
            c.addDataSourceProperty("connectTimeout", "3")
            c.addDataSourceProperty("socketTimeout", "5")
            return ObservedPool(c)
        }

        private fun call(target: Any, method: Method, args: Array<Any?>?): Any? {
            try {
                return if (args == null) method.invoke(target) else method.invoke(target, *args)
            } catch (failure: InvocationTargetException) {
                throw failure.cause!!
            }
        }
    }
}
