package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterObservationRecorder
import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterReads
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
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.sql.SQLException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.HexFormat
import java.util.Properties

/** Two actual-PG contracts on a new P23 claim. Only inserts are attempted, and all test inserts roll back. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Execution(ExecutionMode.SAME_THREAD)
class OwnedPostgresqlShelterObservationTest {
    private val levels = LinkedHashMap<ch.qos.logback.classic.Logger, ch.qos.logback.classic.Level?>()
    private lateinit var settings: Settings
    private var pool: HikariDataSource? = null
    private lateinit var jdbc: JdbcTemplate
    private lateinit var manager: JdbcTransactionManager
    private lateinit var recorder: JdbcShelterObservationRecorder
    private lateinit var reader: JdbcShelterReads
    private lateinit var baseline: Map<String, String>
    private var nextAllowed = false
    private var caseEntered = false
    private var completedBody = false
    private var nextOrder = 1

    @BeforeAll
    fun given__explicit_owned_claim__when__preparing__then__bind_only_the_new_p23_runtime_and_existing_recorder() {
        quiet()
        observed("setup") {
            settings = Settings.load()
            javaClass.getResourceAsStream("/owned-postgresql/shelter-observation-owned-schema.sql").use { input ->
                check(input != null && SQL_SHA == sha(input.readAllBytes()), "Exact frozen P23 SQL resource is mandatory")
            }
            val config = HikariConfig()
            config.poolName = "owned-p23-shelter-observation"
            config.jdbcUrl = settings.url
            config.username = settings.runtime
            config.password = settings.password
            config.maximumPoolSize = 1
            config.minimumIdle = 0
            config.initializationFailTimeout = -1
            config.connectionTimeout = 2500
            config.validationTimeout = 1000
            config.addDataSourceProperty("connectTimeout", "3")
            config.addDataSourceProperty("socketTimeout", "5")
            val source = HikariDataSource(config)
            pool = source
            jdbc = JdbcTemplate(source)
            jdbc.queryTimeout = 5
            manager = JdbcTransactionManager(source)
            // Actual Boot bean construction, without creating or starting its scheduler.
            recorder = ShelterObservationConfiguration().shelterObservationRecorder(source, manager)
            reader = JdbcShelterReads(source, manager, ObjectMapper())
            verifyTargetAndRights()
            check(
                jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.shelters", Int::class.javaObjectType) == 3 &&
                    jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.animals", Int::class.javaObjectType) == 6 &&
                    jdbc.queryForObject(
                        "SELECT count(*) FROM pawbridge_animal.shelter_daily_observations",
                        Int::class.javaObjectType,
                    ) == 2,
                "Exact fresh representative seed is required",
            )
            check(
                today() == jdbc.queryForObject(
                    "SELECT max(observation_date) FROM pawbridge_animal.shelter_daily_observations",
                    LocalDate::class.java,
                ),
                "Preparation must still be on the current database KST date; do not refresh or mutate a stale claim",
            )
            baseline = fingerprints()
            released()
            nextAllowed = true
        }
    }

    @BeforeEach
    fun given__successful_previous_case__when__starting_next__then__require_full_ordered_class(info: TestInfo) {
        val previous = nextAllowed
        nextAllowed = false
        caseEntered = false
        completedBody = false
        check(
            previous && info.testMethod.orElseThrow().getAnnotation(Order::class.java).value == nextOrder,
            "Full ordered P23 class and previous success are mandatory",
        )
        released()
        caseEntered = true
    }

    @AfterEach
    fun given__case_finished__when__auditing__then__restore_exact_seed_by_rollback_and_return_connection() {
        if (!caseEntered) {
            return
        }
        observed("after-case") {
            released()
            check(baseline == fingerprints(), "All three source tables must match the original seed after rollback")
            released()
            check(completedBody, "A failed P23 case fences later work")
            nextOrder++
            nextAllowed = true
        }
    }

    @AfterAll
    fun given__synchronous_work_settled__when__closing__then__close_only_the_idle_owned_pool_without_deleting_rows() {
        try {
            released()
            val source = pool
            if (source != null) {
                source.close()
                check(source.isClosed, "The P23 pool must close normally")
            }
            check(nextOrder == 3 && nextAllowed, "Exactly both P23 contracts must complete")
        } finally {
            levels.forEach { (logger, level) -> logger.level = level }
        }
    }

    @Test
    @Order(1)
    fun `빈·혼합·기관측 보호소가 주어졌을 때__한 트랜잭션에서 두 번 기록하면__첫 KST 스냅샷과 소비자 응답을 보존하고 롤백한다`() {
        contract("snapshot-and-consumer") {
            val date = today()
            val first = observation(103, date)
            check(first != null && first.count == 17L, "The prepared first observation must be present")
            check(
                reader.observations(101, date, date).isEmpty(),
                "An existing empty shelter starts with absence rather than a fabricated zero",
            )
            released()
            val outer = TransactionTemplate(manager)
            outer.timeout = 30
            outer.executeWithoutResult { status ->
                val holder = bound()
                val xid: Long = jdbc.queryForObject("SELECT txid_current()", Long::class.javaObjectType)!!
                val pid: Int = jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.javaObjectType)!!
                val before = jdbc.queryForObject("SELECT statement_timestamp()", OffsetDateTime::class.java)!!
                check(recorder.recordToday() == 2, "Only shelters without a first observation today may be inserted")
                val after = jdbc.queryForObject("SELECT statement_timestamp()", OffsetDateTime::class.java)!!
                check(
                    bound() === holder &&
                        jdbc.queryForObject("SELECT txid_current()", Long::class.javaObjectType) == xid &&
                        jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.javaObjectType) == pid,
                    "Recorder must join the same actual writer transaction and connection",
                )
                val empty = observation(101, date)
                val mixed = observation(102, date)
                check(
                    empty != null && empty.count == 0L && mixed != null && mixed.count == 2L,
                    "LEFT JOIN must retain zero shelters and count only the two PROTECT animals",
                )
                check(empty!!.at == mixed!!.at, "The single INSERT statement must use one statement timestamp")
                for (row in listOf(empty, mixed)) {
                    check(
                        !row.at.isBefore(before) && !row.at.isAfter(after) &&
                            row.date == row.at.atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate(),
                        "New date and instant must come from the same database KST statement",
                    )
                }
                check(first == observation(103, date), "Existing today's count and observed_at must not be rewritten")
                val saved = fingerprints()
                check(recorder.recordToday() == 0, "Second record on the same database KST day must insert nothing")
                check(saved == fingerprints(), "Second record must not alter any observation or source row")
                val rows = reader.observations(102, date.minusDays(1), date)
                check(
                    rows.size == 2 && rows.first().date == date.minusDays(1) && rows.first().protectedCount == 8L &&
                        rows.last().date == date && rows.last().protectedCount == 2L &&
                        rows.last().observedAt!!.toInstant() == mixed.at.toInstant(),
                    "Actual existing observations reader must consume sorted original fields",
                )
                val zero = reader.observations(101, date, date)
                check(zero.size == 1 && zero.first().protectedCount == 0L, "Actual consumer must distinguish observed zero from absence")
                status.setRollbackOnly()
            }
            released()
            check(baseline == fingerprints(), "Positive producer inserts must be physically rolled back without cleanup writes")
            val restored = reader.observations(102, date.minusDays(1), date)
            check(
                restored.size == 1 && restored.first().protectedCount == 8L,
                "Standalone existing read transaction must see only the original history after rollback",
            )
        }
    }

    @Test
    @Order(2)
    fun `INSERT 권한이 있는 읽기 전용 트랜잭션이 주어졌을 때__같은 기록기로 쓰면__Postgres가 거절하고 부분 행 없이 롤백한다`() {
        contract("server-read-only") {
            val readOnly = TransactionTemplate(manager)
            readOnly.isReadOnly = true
            readOnly.timeout = 30
            var rejected = false
            try {
                readOnly.executeWithoutResult {
                    check(
                        TransactionSynchronizationManager.isCurrentTransactionReadOnly() &&
                            "on" == jdbc.queryForObject("SELECT current_setting('transaction_read_only')", String::class.java),
                        "Actual server transaction must already be read-only",
                    )
                    recorder.recordToday()
                }
            } catch (failure: DataAccessException) {
                rejected = sqlState(failure, "25006")
            }
            check(rejected, "Actual PostgreSQL read-only SQLSTATE25006 is required rather than a permission or wiring failure")
            released()
            check(baseline == fingerprints(), "Read-only rejection must leave the original three-table baseline unchanged")
        }
    }

    private fun today(): LocalDate {
        return jdbc.queryForObject("SELECT (statement_timestamp() AT TIME ZONE 'Asia/Seoul')::date", LocalDate::class.java)!!
    }

    private data class Observation(val date: LocalDate, val at: OffsetDateTime, val count: Long)

    private fun observation(shelter: Long, date: LocalDate): Observation? {
        val rows = jdbc.query(
            "SELECT observation_date,observed_at,protected_count FROM pawbridge_animal.shelter_daily_observations WHERE shelter_id=? AND observation_date=?",
            RowMapper { row, _ ->
                Observation(row.getObject(1, LocalDate::class.java), row.getObject(2, OffsetDateTime::class.java), row.getLong(3))
            },
            shelter,
            date,
        )
        check(rows.size <= 1, "One observation per shelter and date is mandatory")
        return if (rows.isEmpty()) null else rows.first()
    }

    private fun bound(): ConnectionHolder {
        val current = TransactionSynchronizationManager.getResource(pool!!)
        check(
            current is ConnectionHolder && TransactionSynchronizationManager.isActualTransactionActive(),
            "Actual writer resource must be bound",
        )
        val holder = current as ConnectionHolder
        try {
            check(!holder.connection.autoCommit, "Actual writer connection must not auto-commit")
        } catch (failure: SQLException) {
            throw AssertionError("Bound connection state unavailable")
        }
        return holder
    }

    private fun fingerprints(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (table in TABLES) {
            val order = if (table.endsWith("shelter_daily_observations")) "shelter_id,observation_date" else "id"
            result[table] = jdbc.queryForObject(
                "SELECT md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY " + order + "),'[]'::jsonb)::text) FROM " + table + " t",
                String::class.java,
            )!!
        }
        return result
    }

    private fun released() {
        val source = pool
        check(
            !TransactionSynchronizationManager.isActualTransactionActive() &&
                (source == null || !TransactionSynchronizationManager.hasResource(source)),
            "No P23 transaction resource may remain bound",
        )
        if (source != null && !source.isClosed && source.hikariPoolMXBean != null) {
            check(
                source.hikariPoolMXBean.activeConnections == 0 && source.hikariPoolMXBean.threadsAwaitingConnection == 0,
                "Active work remains; do not close its pool",
            )
        }
    }

    private fun verifyTargetAndRights() {
        val identity = jdbc.queryForMap(
            "SELECT current_database() db,current_user actor,current_setting('server_version_num') version,pg_get_userbyid(datdba) owner FROM pg_database WHERE datname=current_database()",
        )
        check(
            settings.db == identity["db"] && settings.runtime == identity["actor"] &&
                settings.owner() == identity["owner"] && "170011" == identity["version"],
            "Exact new P23 database, runtime, NOLOGIN owner and PG17.11 are required",
        )
        check(
            settings.marker == jdbc.queryForObject("SELECT marker FROM public.pb_shelter_observation_fixture", String::class.java),
            "Exact P23 marker is mandatory",
        )
        check(
            true == jdbc.queryForObject(
                "SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit FROM pg_roles WHERE rolname=current_user",
                Boolean::class.javaObjectType,
            ),
            "Runtime cannot inherit administrative capabilities",
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
            "Separate NOLOGIN fixture owner must be inaccessible",
        )
        check(
            false == jdbc.queryForObject(
                "SELECT has_database_privilege(current_user,current_database(),'CREATE,TEMP')",
                Boolean::class.javaObjectType,
            ),
            "Runtime database and TEMP DDL are forbidden",
        )
        for (schema in listOf("public", "pawbridge_animal")) {
            check(
                false == jdbc.queryForObject(
                    "SELECT has_schema_privilege(current_user,?,'CREATE')",
                    Boolean::class.javaObjectType,
                    schema,
                ),
                "Runtime schema creation is forbidden",
            )
        }
        check(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE schemaname='pawbridge_animal'",
                Int::class.javaObjectType,
            ) == 3,
            "Only the three selected Animal business tables may exist",
        )
        for (table in TABLES) {
            check(
                true == jdbc.queryForObject(
                    "SELECT has_table_privilege(current_user,?,'SELECT')",
                    Boolean::class.javaObjectType,
                    table,
                ),
                "Selected source SELECT must be available",
            )
            for (right in listOf("UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                check(
                    false == jdbc.queryForObject(
                        "SELECT has_table_privilege(current_user,?,?)",
                        Boolean::class.javaObjectType,
                        table,
                        right,
                    ),
                    "Runtime source mutation and destructive privileges are forbidden",
                )
            }
            for (right in listOf("UPDATE", "REFERENCES")) {
                check(
                    false == jdbc.queryForObject(
                        "SELECT has_any_column_privilege(current_user,?,?)",
                        Boolean::class.javaObjectType,
                        table,
                        right,
                    ),
                    "Column grants cannot bypass immutable sources",
                )
            }
            if (!table.endsWith("shelter_daily_observations")) {
                check(
                    false == jdbc.queryForObject(
                        "SELECT has_any_column_privilege(current_user,?,'INSERT')",
                        Boolean::class.javaObjectType,
                        table,
                    ),
                    "Runtime cannot insert Animal or Shelter source rows",
                )
            }
        }
        for (column in listOf("shelter_id", "observation_date", "observed_at", "protected_count")) {
            check(
                true == jdbc.queryForObject(
                    "SELECT has_column_privilege(current_user,'pawbridge_animal.shelter_daily_observations',?,'INSERT')",
                    Boolean::class.javaObjectType,
                    column,
                ),
                "Only the four original observation INSERT columns must be granted",
            )
        }
    }

    private fun contract(phase: String, work: Checked) {
        observed(phase) {
            work.run()
            completedBody = true
        }
    }

    private fun quiet() {
        for (name in listOf("com.zaxxer.hikari", "org.springframework.jdbc", "org.postgresql")) {
            val logger = LoggerFactory.getLogger(name) as ch.qos.logback.classic.Logger
            levels[logger] = logger.level
            logger.level = ch.qos.logback.classic.Level.OFF
        }
    }

    private fun interface Checked {
        fun run()
    }

    private class Settings(
        val url: String,
        val db: String,
        val runtime: String,
        val password: String,
        val marker: String,
    ) {
        fun owner(): String = db.replace("pb_shelter_obs_owned_", "pb_shelter_obs_owner_")

        override fun toString(): String = "OwnedShelterObservationSettings[redacted]"

        companion object {
            fun load(): Settings {
                check("P23_PG_SETTINGS" == System.getenv("PAWBRIDGE_OWNED_SETTINGS_ENV"), "Only explicit P23 selector is accepted")
                val configured = System.getenv("P23_PG_SETTINGS")
                check(configured != null && configured.isNotBlank(), "P23 settings are mandatory without skip or fallback")
                val file = Path.of(configured)
                val expected = Path.of("/private/tmp/pawbridge-owned-pg-night-20261020/shelter-observation/shelter-observation.properties")
                check(file == expected && file.normalize() == file, "Only exact fresh shelter observation settings are accepted")
                privateFile(file)
                privateFile(RESUME)
                val json = ObjectMapper()
                check(RESUME_SHA == sha(Files.readAllBytes(RESUME)), "Only fresh approved Root resume receipt is accepted")
                val resumed = json.readTree(Files.readAllBytes(RESUME))
                check(
                    CID == resumed.path("cid").asText() && IMAGE == resumed.path("image").asText() &&
                        OWNER == resumed.path("owner").asText() && "127.0.0.1" == resumed.path("host").asText() &&
                        resumed.path("port").asInt() == 55437 && resumed.path("ready").asBoolean() &&
                        !resumed.path("oldTmpfsRowsReused").asBoolean() && resumed.path("noHostMount").asBoolean(),
                    "Fresh owned server identity must match",
                )
                check(
                    Files.getPosixFilePermissions(file.parent) ==
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE) &&
                        System.getProperty("user.name") == Files.getOwner(file.parent).name,
                    "Exclusive current-owner claim0700 is required",
                )
                val values = Properties()
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input -> values.load(input) }
                check(
                    values.stringPropertyNames() == setOf("jdbcUrl", "username", "password", "database", "marker"),
                    "Exactly five private settings fields are accepted",
                )
                val db = required(values, "database")
                val runtime = required(values, "username")
                val url = required(values, "jdbcUrl")
                val marker = required(values, "marker")
                check(
                    db.matches(Regex("pb_shelter_obs_owned_[0-9a-f]{12}")) &&
                        runtime == db.replace("pb_shelter_obs_owned_", "pb_shelter_obs_runtime_") &&
                        url == "jdbc:postgresql://127.0.0.1:55437/" + db &&
                        marker.matches(Regex("[0-9a-f]{32}")),
                    "Exact nonce runtime/database/loopback/marker are mandatory",
                )
                val state = file.resolveSibling("state.json")
                privateFile(state)
                val claim = json.readTree(Files.readAllBytes(state))
                check(
                    db == claim.path("database").asText() && runtime == claim.path("runtime").asText() &&
                        db.replace("pb_shelter_obs_owned_", "pb_shelter_obs_owner_") == claim.path("fixtureOwner").asText() &&
                        marker == claim.path("marker").asText() &&
                        "shelter-observation-v1" == claim.path("purpose").asText() &&
                        "schema-ready" == claim.path("status").asText() &&
                        claim.path("schemaInitialized").asBoolean() &&
                        SQL_SHA == claim.path("schema_sha256").asText() &&
                        RESUME_SHA == claim.path("resume_receipt_sha256").asText() &&
                        claim.path("port").asInt() == 55437 &&
                        CID == claim.path("containerId").asText() &&
                        IMAGE == claim.path("image").asText() &&
                        OWNER == claim.path("owner").asText(),
                    "Fresh P23 claim must bind exact SQL/Root receipt and runtime identity",
                )
                return Settings(url, db, runtime, required(values, "password"), marker)
            }

            private fun required(values: Properties, name: String): String {
                val value = values.getProperty(name)
                check(value != null && value.isNotBlank(), "Every P23 private setting is mandatory")
                return value
            }
        }
    }

    companion object {
        private const val SQL_SHA = "98a1b79a86c43820beba5d3a8fabeda0845e3231dfb308e88f00a4ae367d3c7f"
        private const val CID = "b1719e47b8c3ff8c334e9df96c86b4f36478eb72022b8e19278d75c9f8a7187c"
        private const val IMAGE = "sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f"
        private const val OWNER = "08b97b84-e0b5-4c6f-a5f8-3d51ec4a2f89"
        private const val RESUME_SHA = "0c471fd664f107524e395b0d414c3e1a1c107f007e1f512194f0562f97c91e47"
        private val RESUME: Path = Path.of("/private/tmp/pawbridge-main-db-integration-owned-20261021/owned-resume-receipt.json")
        private val TABLES: List<String> = listOf(
            "pawbridge_animal.shelters",
            "pawbridge_animal.animals",
            "pawbridge_animal.shelter_daily_observations",
        )

        private fun privateFile(path: Path) {
            check(
                path.isAbsolute && path.normalize() == path && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
                "Exact regular private file is mandatory",
            )
            var current: Path? = path
            while (current != null) {
                check(!Files.isSymbolicLink(current), "No private-file symlink ancestor is allowed")
                current = current.parent
            }
            check(
                Files.getPosixFilePermissions(path) == setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE) &&
                    (Files.getAttribute(path, "unix:nlink") as Number).toInt() == 1 &&
                    System.getProperty("user.name") == Files.getOwner(path).name,
                "Current-owner single-link0600 file is required",
            )
        }

        private fun sqlState(failure: Throwable, state: String): Boolean {
            var current: Throwable? = failure
            while (current != null) {
                if (current is SQLException && state == current.sqlState) {
                    return true
                }
                current = current.cause
            }
            return false
        }

        private fun observed(phase: String, work: Checked) {
            try {
                work.run()
            } catch (failure: AssertionError) {
                throw failure
            } catch (failure: Throwable) {
                throw AssertionError("Owned P23 failed: " + phase + "; type=" + failure.javaClass.name)
            }
        }

        private fun check(valid: Boolean, fixed: String) {
            if (!valid) {
                throw AssertionError(fixed)
            }
        }

        private fun sha(bytes: ByteArray): String {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            } catch (failure: Exception) {
                throw AssertionError("SHA unavailable")
            }
        }
    }
}
