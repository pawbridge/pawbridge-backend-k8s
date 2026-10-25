package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.travel.application.PetTravel
import com.pawbridge.backend.boot.security.PostTokenAuthenticator
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionExecution
import org.springframework.transaction.TransactionExecutionListener
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import java.util.function.Supplier
import javax.sql.DataSource

/** Real SELECT-only owned fixture and the three public MVC reads; no collector or external API. */
@Tag("owned-postgresql")
@Timeout(30)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OwnedPostgresqlAnimalTravelTest {
    private var pool: HikariDataSource? = null
    private lateinit var fixture: JdbcTemplate
    private lateinit var applicationJdbc: ObservedJdbc
    private lateinit var baseline: Map<String, String>
    private val physicalIsolation: MutableList<String> = ArrayList()

    private fun pool(): HikariDataSource = pool!!

    @BeforeAll
    fun givenOwnedReadonlyIdentity_whenOpeningFixture_thenRejectOtherTargetsAndPermissions() {
        observed {
            val settings = Settings.load()
            val probe = DriverManagerDataSource(settings.url, settings.username, settings.password)
            val timeouts = Properties()
            timeouts.setProperty("connectTimeout", "3")
            timeouts.setProperty("socketTimeout", "5")
            probe.setConnectionProperties(timeouts)
            verifyIdentity(JdbcTemplate(probe), settings)
            val config = HikariConfig()
            config.jdbcUrl = settings.url
            config.username = settings.username
            config.password = settings.password
            config.poolName = "owned-travel-reader"
            config.maximumPoolSize = 1
            config.minimumIdle = 0
            config.connectionTimeout = 2000
            config.validationTimeout = 1000
            config.initializationFailTimeout = -1
            config.isReadOnly = true
            config.addDataSourceProperty("connectTimeout", "3")
            config.addDataSourceProperty("socketTimeout", "5")
            val source = HikariDataSource(config)
            pool = source
            fixture = JdbcTemplate(source)
            fixture.queryTimeout = 3
            applicationJdbc = ObservedJdbc(source)
            check(
                fixture.queryForObject("SELECT count(*) FROM pawbridge_animal.pet_travel_regions", Long::class.javaObjectType) == 3L,
                "Exactly the synthetic numeric-order regions must exist",
            )
            check(
                fixture.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.pet_travel_targets WHERE provider='OTHER_PROVIDER' AND content_id='990001'",
                    Long::class.javaObjectType,
                ) == 1L,
                "The protected foreign-provider seed must exist",
            )
            baseline = fingerprint()
            released()
        }
    }

    @BeforeEach
    fun givenPreservedSourceRows_whenStartingCase_thenRequireTheOriginalBaseline() {
        check(fingerprint() == baseline, "Every source table must match the fixture baseline")
        released()
    }

    @AfterEach
    fun givenSelectOnlyRequests_whenFinishingCase_thenKeepAllOwnedAndForeignRowsWithoutCleanupDml() {
        check(fingerprint() == baseline, "Travel reads must preserve all seven source tables including foreign rows")
        released()
    }

    @AfterAll
    fun givenCompletedRequests_whenClosing_thenCloseOnlyTheOwnedPool() {
        val source = pool
        if (source != null) {
            source.close()
            check(source.isClosed, "The owned reader pool must close")
        }
    }

    @Test
    fun givenSyntheticRegionsAndPublishedAndPreparingRows_whenReadingThreePublicRoutes_thenPreserveFilteringCountRowsAndCompleteWire() {
        http { mvc ->
            val regions = send(mvc, ROOT + "/regions", 200)
            check(
                fields(regions) == setOf("items", "fetchedAt", "availability") &&
                    "READY" == regions.path("availability").asText(),
                "Regions must use the original three-field wire",
            )
            check(
                values(regions.path("items"), "code") == listOf("20", "99", "100"),
                "Regions must sort numerically rather than lexicographically",
            )
            check(fields(regions.path("items").get(0)) == setOf("code", "name"), "Region entries must retain their two fields")
            val page = send(mvc, ROOT + "?areaCode=20&page=0", 200)
            listObservation(true)
            check(
                fields(page) == setOf(
                    "areaCode", "items", "previewOnly", "fetchedAt", "availability", "page", "size", "totalElements", "totalPages",
                ),
                "Places must preserve the original page wire",
            )
            check(
                page.path("items").size() == 10 && page.path("page").asInt() == 0 && page.path("size").asInt() == 10 &&
                    page.path("totalElements").asLong() == 11L && page.path("totalPages").asLong() == 2L &&
                    page.path("previewOnly").asBoolean() && "READY" == page.path("availability").asText(),
                "Count and data must apply identical provider/type/visibility/area filters",
            )
            check(
                values(page.path("items"), "contentId") ==
                    listOf("910001", "910002", "910003", "910004", "910005", "910006", "910007", "910008", "910009", "910010"),
                "The page must use title followed by content ID without leaking excluded or foreign-provider rows",
            )
            check(
                fields(page.path("items").get(0)) == setOf("contentId", "title", "address", "imageUrl") &&
                    "https://tong.visitkorea.or.kr/cms/resource/20/MAIN.jpg" == page.path("items").get(0).path("imageUrl").asText(),
                "Place cards must retain the original four-field licensed image wire",
            )
            val last = send(mvc, ROOT + "?areaCode=20&page=1", 200)
            listObservation(true)
            check(
                values(last.path("items"), "contentId") == listOf("910011") && last.path("totalElements").asLong() == 11L,
                "A partially filled final page must retain the exact total",
            )
            val beyond = send(mvc, ROOT + "?areaCode=20&page=2", 200)
            listObservation(false)
            check(
                beyond.path("items").isArray && beyond.path("items").isEmpty && beyond.path("totalElements").asLong() == 11L,
                "Out-of-range pages must keep the count without querying rows",
            )
            val empty = send(mvc, ROOT + "?areaCode=99", 200)
            listObservation(false)
            check(
                empty.path("items").isArray && empty.path("items").isEmpty && empty.path("totalElements").asLong() == 0L &&
                    empty.path("totalPages").asLong() == 0L && empty.path("fetchedAt").isNull &&
                    "PREPARING" == empty.path("availability").asText(),
                "An unfinished empty region must retain its source availability",
            )
            val detail = send(mvc, ROOT + "/910001", 200)
            check(
                fields(detail) == setOf(
                    "place", "overview", "conditions", "petInformationAvailable", "source", "fetchedAt",
                    "petInformationFetchedAt", "petInformationStatus", "visitInformation", "images", "imagesStatus",
                    "imagesFetchedAt",
                ),
                "Detail must retain the original twelve-field wire",
            )
            check(
                "Alpha" == detail.path("place").path("title").asText() &&
                    "synthetic current address" == detail.path("place").path("address").asText() &&
                    "synthetic overview" == detail.path("overview").asText() &&
                    "DOGS" == detail.path("conditions").path("areas").asText() &&
                    detail.path("petInformationAvailable").asBoolean() &&
                    "KOREA_TOURISM_ORGANIZATION" == detail.path("source").asText() &&
                    "READY" == detail.path("petInformationStatus").asText(),
                "Current basic fields, retained overview and valid independent pet details must merge",
            )
            check(
                fields(detail.path("conditions")).size == 7 && "READY" == detail.path("visitInformation").path("status").asText() &&
                    detail.path("visitInformation").path("additionalItems").size() == 1 &&
                    "0000-0000" == detail.path("visitInformation").path("informationCenter").asText(),
                "Visit JSONB fields and incomplete information entries must use the source mapping",
            )
            check(
                detail.path("images").size() == 2 && "READY" == detail.path("imagesStatus").asText() &&
                    values(detail.path("images"), "name") == listOf("first", "second") &&
                    "https://tong.visitkorea.or.kr/cms/resource/20/FIRST.jpg" ==
                    detail.path("images").get(0).path("originalUrl").asText(),
                "Gallery order, licenses, host filter and safe HTTP upgrade must survive real PostgreSQL JSONB reads",
            )
            check(
                "910012" == send(mvc, ROOT + "/910012", 200).path("place").path("contentId").asText(),
                "Detail must not invent the list-only content-type filter",
            )
            send(mvc, ROOT + "/999999", 404)
        }
    }

    @Test
    fun givenMinimumSelectPrivilegesAndOneReaderConnection_whenListAndErrorThenRecoveryRun_thenObservePhysicalReadOnlyRepeatableReadAndNoEscapedBinding() {
        http { mvc ->
            send(mvc, ROOT + "?areaCode=20", 200)
            listObservation(true)
            send(mvc, ROOT + "?areaCode=777", 400)
            check(physicalIsolation == listOf("repeatable read"), "Invalid region failure must still finish its single list transaction")
            send(mvc, ROOT + "/910016", 503)
            check(physicalIsolation.size == 1, "Missing required stored fields must fail inside one real readonly transaction")
            send(mvc, ROOT + "/regions", 200)
            check(physicalIsolation.size == 1, "A reader must recover immediately after the unavailable detail")
            check(fingerprint() == baseline, "Neither error nor recovery may write any source or foreign row")
        }
    }

    private fun listObservation(rows: Boolean) {
        check(physicalIsolation == listOf("repeatable read"), "List count and rows require one actual PostgreSQL REPEATABLE READ transaction")
        check(
            applicationJdbc.calls.count { call -> "count" == call } == 1 &&
                applicationJdbc.calls.count { call -> "rows" == call } == (if (rows) 1 else 0),
            "The actual catalog must execute count and bounded rows according to the requested page",
        )
    }

    private fun http(action: HttpCheck) {
        observed {
            val source = pool()
            val manager = JdbcTransactionManager(source)
            manager.isEnforceReadOnly = true
            manager.addListener(
                object : TransactionExecutionListener {
                    override fun afterBegin(tx: TransactionExecution, failure: Throwable?) {
                        check(
                            failure == null && TransactionSynchronizationManager.isActualTransactionActive() &&
                                TransactionSynchronizationManager.isCurrentTransactionReadOnly() &&
                                TransactionSynchronizationManager.hasResource(source),
                            "Every catalog operation must bind the existing reader transaction",
                        )
                        check(
                            "on" == fixture.queryForObject("SHOW transaction_read_only", String::class.java) &&
                                source.hikariPoolMXBean.activeConnections == 1,
                            "PostgreSQL must observe readonly on the single bound reader connection",
                        )
                        physicalIsolation.add(fixture.queryForObject("SHOW transaction_isolation", String::class.java)!!)
                    }
                },
            )
            val reads = TransactionTemplate(manager)
            reads.isReadOnly = true
            reads.timeout = 3
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
                .withUserConfiguration(AnimalTravelConfiguration::class.java, ReadSecurityConfiguration::class.java)
                .withBean(
                    "dataSource",
                    DataSource::class.java,
                    Supplier { source },
                    BeanDefinitionCustomizer { bean -> bean.setDestroyMethodName("") },
                )
                .withBean("readJdbc", JdbcTemplate::class.java, Supplier { applicationJdbc })
                .withBean("transactionManager", JdbcTransactionManager::class.java, Supplier { manager })
                .withBean("readOnlyTransaction", TransactionTemplate::class.java, Supplier { reads })
                .withBean(
                    PostTokenAuthenticator::class.java,
                    Supplier {
                        PostTokenAuthenticator(
                            Keys.hmacShaKeyFor(
                                "owned-travel-public-synthetic-security-key-20261021".toByteArray(StandardCharsets.UTF_8),
                            ),
                        )
                    },
                )
                .withPropertyValues(
                    "pawbridge.animal-travel.enabled=true",
                    "pawbridge.animal-query.backend=postgresql",
                    "spring.sql.init.mode=never",
                    "spring.jmx.enabled=false",
                )
                .run { context ->
                    check(
                        context.startupFailure == null && context.getBeansOfType(PetTravel::class.java).size == 1,
                        "The real travel configuration and secured public controller must start",
                    )
                    val list = context.getBean("animalTravelListReadOnlyTransaction", TransactionTemplate::class.java)
                    check(
                        list.transactionManager === manager && list.isReadOnly && list.timeout == 3 &&
                            list.isolationLevel == TransactionDefinition.ISOLATION_REPEATABLE_READ,
                        "List must change only the existing reader transaction isolation",
                    )
                    try {
                        action.run(
                            MockMvcBuilders.webAppContextSetup(context)
                                .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
                                .build(),
                        )
                    } catch (failure: AssertionError) {
                        throw failure
                    } catch (failure: Exception) {
                        throw AssertionError("Owned travel HTTP failed: " + failure.javaClass.name)
                    }
                }
            check(!source.isClosed, "Context close must preserve the externally owned reader pool")
            released()
        }
    }

    private fun send(mvc: MockMvc, path: String, expected: Int): JsonNode {
        applicationJdbc.calls.clear()
        physicalIsolation.clear()
        released()
        val response = mvc.perform(
            MockMvcRequestBuilders.get(path)
                .servletPath(path.split("?", limit = 2)[0])
                .header("X-User-Id", "990001")
                .header("X-User-Role", "ROLE_ADMIN")
                .header("X-Internal-Api-Key", "untrusted"),
        )
            .andReturn().response
        check(response.status == expected, "Unexpected travel HTTP status: expected=" + expected + ",actual=" + response.status)
        check(response.getHeader("Set-Cookie") == null, "Public travel reads must remain stateless")
        if (expected >= 400) {
            check("no-store" == response.getHeader("Cache-Control"), "Travel errors must remain noncacheable")
        }
        val result = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8))
        if (expected == 404) {
            check("PET_TRAVEL_NOT_FOUND" == result.path("code").asText(), "Missing detail must retain the source404 code")
        }
        if (expected == 400) {
            check("PET_TRAVEL_INVALID_REQUEST" == result.path("code").asText(), "Invalid filter must retain the source400 code")
        }
        if (expected == 503) {
            check("PET_TRAVEL_UNAVAILABLE" == result.path("code").asText(), "Stored unavailable detail must retain the fixed503 code")
        }
        released()
        return result
    }

    private inner class ObservedJdbc(source: DataSource) : JdbcTemplate(source) {
        val calls: MutableList<String> = ArrayList()

        init {
            queryTimeout = 3
        }

        private fun observe(sql: String) {
            check(
                TransactionSynchronizationManager.isActualTransactionActive() &&
                    TransactionSynchronizationManager.isCurrentTransactionReadOnly() &&
                    TransactionSynchronizationManager.hasResource(pool()),
                "Catalog SQL must run inside the bound readonly reader transaction",
            )
            if (sql.startsWith("SELECT COUNT(*) ")) {
                calls.add("count")
            }
            if (sql.contains(" LIMIT 10 OFFSET ?")) {
                calls.add("rows")
            }
        }

        override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>): MutableList<T> {
            observe(sql)
            return super.query(sql, rowMapper)
        }

        override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): MutableList<T> {
            observe(sql)
            return super.query(sql, rowMapper, *args)
        }

        override fun <T : Any?> queryForObject(sql: String, requiredType: Class<T>, vararg args: Any?): T? {
            observe(sql)
            return super.queryForObject(sql, requiredType, *args)
        }

        override fun <T : Any?> queryForObject(sql: String, rowMapper: RowMapper<T>): T? {
            observe(sql)
            return super.queryForObject(sql, rowMapper)
        }
    }

    private fun fingerprint(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        TABLE_KEYS.forEach { (table, key) ->
            result[table] = fixture.queryForObject(
                "SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(r)::text,',' ORDER BY " + key + "),'')) FROM pawbridge_animal." + table + " r",
                String::class.java,
            )!!
        }
        return result
    }

    private fun released() {
        val source = pool()
        check(
            !TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.hasResource(source) &&
                source.hikariPoolMXBean.activeConnections == 0 && source.hikariPoolMXBean.threadsAwaitingConnection == 0,
            "Every public operation must release its actual transaction and reader connection",
        )
    }

    private fun interface Checked {
        fun run()
    }

    private fun interface HttpCheck {
        fun run(mvc: MockMvc)
    }

    private class Settings(
        val url: String,
        val database: String,
        val username: String,
        val password: String,
        val marker: String,
    ) {
        override fun toString(): String = "OwnedTravelSettings[redacted]"

        companion object {
            fun load(): Settings {
                val value = System.getenv("P14_PG_SETTINGS")
                check(value != null && value.isNotBlank(), "P14_PG_SETTINGS is mandatory without skip")
                val file = Path.of(value)
                check(
                    file.isAbsolute && file.normalize() == file &&
                        Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file),
                    "Settings must be an absolute regular non-symlink file",
                )
                check(
                    Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS) ==
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE) &&
                        (Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1,
                    "Settings require exact0600 and one hard link",
                )
                check(
                    System.getProperty("user.name") == Files.getOwner(file).name &&
                        Files.getOwner(file) == Files.getOwner(file.parent),
                    "Settings and parent must belong to the process owner",
                )
                var parent: Path? = file.parent
                while (parent != null) {
                    check(!Files.isSymbolicLink(parent), "Settings ancestors must not be symlinks")
                    parent = parent.parent
                }
                val permissions = Files.getPosixFilePermissions(file.parent, LinkOption.NOFOLLOW_LINKS)
                check(
                    permissions == setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
                    "Settings parent must be private0700",
                )
                val values = Properties()
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input -> values.load(input) }
                check(
                    values.stringPropertyNames() == setOf("jdbcUrl", "database", "username", "password", "marker"),
                    "Only the dedicated five settings are accepted",
                )
                val database = required(values, "database")
                val url = required(values, "jdbcUrl")
                val username = required(values, "username")
                val marker = required(values, "marker")
                check(
                    database.matches(Regex("pb_travel_owned_[0-9a-f]{12}")) &&
                        url == "jdbc:postgresql://127.0.0.1:52620/" + database &&
                        username == "pb_travel_reader" && marker.matches(Regex("[0-9a-f]{32}")),
                    "Only the exact new loopback fixture and reader are accepted",
                )
                return Settings(url, database, username, required(values, "password"), marker)
            }

            private fun required(values: Properties, key: String): String {
                val value = values.getProperty(key)
                check(value != null && value.isNotBlank(), "Every owned setting is required")
                return value
            }
        }
    }

    companion object {
        private const val ROOT = "/api/v1/places"
        private val JSON: ObjectMapper = ObjectMapper().findAndRegisterModules()
        private val TABLE_KEYS: Map<String, String> = mapOf(
            "pet_travel_regions" to "code",
            "pet_travel_collection_state" to "id",
            "pet_travel_targets" to "provider,content_id",
            "pet_travel_places" to "provider,content_id",
            "pet_travel_pet_details" to "provider,content_id",
            "pet_travel_visit_details" to "provider,content_id",
            "pet_travel_images" to "provider,content_id,serial_number",
        )

        private fun fields(value: JsonNode): Set<String> {
            val names = HashSet<String>()
            value.fieldNames().forEachRemaining { name -> names.add(name) }
            return names
        }

        private fun values(array: JsonNode, field: String): List<String> {
            val values = ArrayList<String>()
            array.forEach { row -> values.add(row.path(field).asText()) }
            return values
        }

        private fun verifyIdentity(jdbc: JdbcTemplate, settings: Settings) {
            jdbc.queryTimeout = 3
            val identity = jdbc.queryForMap(
                "SELECT current_database() db,current_user actor,session_user session,current_setting('server_version_num') version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) owner",
            )
            check(
                settings.database == identity["db"] && settings.username == identity["actor"] &&
                    settings.username == identity["session"] && "170011" == identity["version"] &&
                    settings.username != identity["owner"],
                "Only the exact new owned reader identity is accepted",
            )
            val markers = jdbc.query(
                "SELECT marker FROM public.pawbridge_owned_fixture",
                RowMapper { row, _ -> row.getString(1) },
            )
            check(markers.size == 1 && settings.marker == markers.first(), "Only the exact owned marker is accepted")
            check(
                true == jdbc.queryForObject(
                    "SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT pg_has_role(current_user,(SELECT datdba FROM pg_database WHERE datname=current_database()),'MEMBER') FROM pg_roles WHERE rolname=current_user",
                    Boolean::class.javaObjectType,
                ),
                "Reader cannot inherit owner or administrative access",
            )
            for (table in TABLE_KEYS.keys) {
                check(
                    true == jdbc.queryForObject(
                        "SELECT has_table_privilege(current_user,?,'SELECT') AND NOT has_table_privilege(current_user,?,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT has_any_column_privilege(current_user,?,'INSERT,UPDATE,REFERENCES')",
                        Boolean::class.javaObjectType,
                        "pawbridge_animal." + table,
                        "pawbridge_animal." + table,
                        "pawbridge_animal." + table,
                    ),
                    "The seven source tables must be SELECT-only",
                )
            }
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind IN ('r','p','v','m','f') AND (has_table_privilege(current_user,c.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') OR has_any_column_privilege(current_user,c.oid,'INSERT,UPDATE,REFERENCES') OR ((has_table_privilege(current_user,c.oid,'SELECT') OR has_any_column_privilege(current_user,c.oid,'SELECT')) AND (n.nspname||'.'||c.relname) NOT IN ('public.pawbridge_owned_fixture','pawbridge_animal.pet_travel_regions','pawbridge_animal.pet_travel_collection_state','pawbridge_animal.pet_travel_targets','pawbridge_animal.pet_travel_places','pawbridge_animal.pet_travel_pet_details','pawbridge_animal.pet_travel_visit_details','pawbridge_animal.pet_travel_images')))",
                    Long::class.javaObjectType,
                ) == 0L,
                "Reader must have no writes or unrelated reads",
            )
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind='S' AND has_sequence_privilege(current_user,c.oid,'USAGE,SELECT,UPDATE')",
                    Long::class.javaObjectType,
                ) == 0L,
                "Reader must have no sequence privileges",
            )
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_namespace WHERE nspname !~ '^pg_' AND nspname<>'information_schema' AND has_schema_privilege(current_user,oid,'CREATE')",
                    Long::class.javaObjectType,
                ) == 0L,
                "Reader must not create schema objects",
            )
            check(
                "on" == jdbc.queryForObject("SHOW transaction_read_only", String::class.java),
                "The role must default to physical readonly before application pool creation",
            )
        }

        private fun check(condition: Boolean, message: String) {
            if (!condition) {
                throw AssertionError(message)
            }
        }

        private fun observed(action: Checked) {
            try {
                action.run()
            } catch (failure: AssertionError) {
                throw failure
            } catch (failure: Throwable) {
                throw AssertionError("Owned travel observation failed: " + failure.javaClass.name)
            }
        }
    }
}
