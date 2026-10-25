package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.boot.security.PostTokenAuthenticator
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.mock.web.MockMultipartFile
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.TransactionExecution
import org.springframework.transaction.TransactionExecutionListener
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.sql.Connection
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.sql.DataSource

/** Candidate-only read-only PG/MVC/local-Python transport observations, never actual AI or production. */
@Tag("owned-postgresql")
@Timeout(20)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OwnedPostgresqlAnimalLostSearchTest {
    private var pool: HikariDataSource? = null
    private lateinit var applicationSource: ObservedSource
    private lateinit var fixture: JdbcTemplate
    private lateinit var baseline: Map<String, String>

    private fun pool(): HikariDataSource = pool!!

    @BeforeAll
    fun openOnlyTheVerifiedReadonlyFixture() {
        observed {
            val settings = Settings.load()
            val config = HikariConfig()
            config.jdbcUrl = settings.url
            config.username = settings.username
            config.password = settings.password
            config.poolName = "owned-lost-search-reader"
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
            fixture.queryTimeout = 5
            verifyIdentity(settings)
            applicationSource = ObservedSource(source)
            baseline = fingerprint()
            check(
                fixture.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.animals WHERE id IN (990110,990111,990112,990113,990114,990115)",
                    Long::class.javaObjectType,
                ) == 6L,
                "All Root-owned filter seeds must exist",
            )
            check(
                fixture.queryForObject("SELECT count(*) FROM pawbridge_animal.animals WHERE id=990999", Long::class.javaObjectType) == 0L,
                "The missing candidate must be absent",
            )
            released()
        }
    }

    @BeforeEach
    fun requireBaseline() {
        check(fingerprint() == baseline, "The readonly baseline must be unchanged before each case")
        released()
    }

    @AfterEach
    fun preserveAllRootOwnedAndForeignRows() {
        check(fingerprint() == baseline, "All Animal/Shelter rows must remain unchanged")
        released()
    }

    @AfterAll
    fun closeOnlyTheOwnedPool() {
        val source = pool
        if (source != null) {
            source.close()
            check(source.isClosed, "The owned reader pool must close")
        }
    }

    @Test
    fun givenCurrentDogsAndStaleRemoteCandidates_whenUploadingPublicly_thenKeepRemoteOrderFirstEvidenceAndCurrentShelterData() {
        http { mvc, stub, begins ->
            stub.reply.set(
                reply(
                    200,
                    listOf(
                        candidate(DOG_B, "DISCOVERY_PLACE_TEXT_MATCH"),
                        candidate(MISSING),
                        candidate(CAT),
                        candidate(DOG_ADOPTED),
                        candidate(ENDED),
                        candidate(DOG_A, "FOUND_ON_OR_AFTER_LOST_DATE"),
                        candidate(DOG_B, "REGISTERED_DESCRIPTION_TEXT_MATCH"),
                    ),
                ),
            )
            val before = begins.get()
            val body = send(mvc, stub, "DOG", false, 200).body
            check(ids(body) == listOf(DOG_B, DOG_A), "Current DB existence, species and active state must filter without reordering")
            check(
                body.path("candidates").get(0).path("matchedEvidence") ==
                    JSON.valueToTree<JsonNode>(listOf("DISCOVERY_PLACE_TEXT_MATCH")),
                "Duplicate IDs must retain the first remote evidence",
            )
            for (value in body.path("candidates")) {
                val animal = value.path("animal")
                val row = fixture.queryForMap(
                    "SELECT breed,status,species,shelter_id FROM pawbridge_animal.animals WHERE id=?",
                    animal.path("id").asLong(),
                )
                check(
                    animal.path("species").asText() == row["species"] && animal.path("status").asText() == row["status"] &&
                        animal.path("shelterId").asLong() == (row["shelter_id"] as Number).toLong() &&
                        "synthetic shelter A" == animal.path("shelterName").asText() &&
                        "02-000-0000" == value.path("shelterPhone").asText() && !value.has("imageScore"),
                    "Public candidates must use current Animal/Shelter values and omit internal scores",
                )
            }
            check(begins.get() == before + 1, "One short reader transaction must begin only after inference")
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun givenCurrentCatAndResolvedRows_whenSelectingTheResolvedOption_thenFilterUsingCurrentDatabaseState(
        includeResolved: Boolean,
    ) {
        http { mvc, stub, _ ->
            stub.reply.set(reply(200, listOf(candidate(CAT_ADOPTED), candidate(DOG_A), candidate(CAT), candidate(MISSING))))
            val body = send(mvc, stub, "CAT", includeResolved, 200).body
            check(
                ids(body) == (if (includeResolved) listOf(CAT_ADOPTED, CAT) else listOf(CAT)),
                "Only the selected species and explicitly allowed resolved states may survive",
            )
        }
    }

    @Test
    fun givenRemote503_whenRetryingTheSameService_thenReturnRetryAfterWithoutDatabaseWorkAndRecoverThePermit() {
        http { mvc, stub, begins ->
            stub.reply.set(Reply(503, "{}".toByteArray(StandardCharsets.UTF_8)))
            val before = begins.get()
            val borrowed = applicationSource.borrowed.get()
            val failure = send(mvc, stub, "DOG", false, 503)
            check(
                "3" == failure.result.response.getHeader("Retry-After") && begins.get() == before &&
                    applicationSource.borrowed.get() == borrowed,
                "Remote failure must return Retry-After and perform no database work",
            )
            stub.reply.set(reply(200, listOf(candidate(DOG_A))))
            check(
                ids(send(mvc, stub, "DOG", false, 200).body) == listOf(DOG_A),
                "The same instance must release its inference permit after failure",
            )
        }
    }

    @Test
    fun givenMoreThanTwentyRemoteCandidates_whenUploading_thenRejectBeforeOpeningAnyReaderTransaction() {
        http { mvc, stub, begins ->
            val candidates = ArrayList<Map<String, Any>>()
            for (i in 0 until 21) {
                candidates.add(candidate(DOG_A))
            }
            stub.reply.set(reply(200, candidates))
            val before = begins.get()
            val borrowed = applicationSource.borrowed.get()
            val response = send(mvc, stub, "DOG", false, 503)
            check(
                "3" == response.result.response.getHeader("Retry-After") && begins.get() == before &&
                    applicationSource.borrowed.get() == borrowed,
                "Oversized remote candidate lists must be rejected with no DB access",
            )
        }
    }

    private fun http(action: HttpCheck) {
        observed {
            PythonStub().use { stub ->
                val emf = AtomicReference<EntityManagerFactory>()
                val begins = AtomicInteger()
                stub.begins = begins
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
                    .withUserConfiguration(AnimalLostSearchConfiguration::class.java, ReadSecurityConfiguration::class.java)
                    .withBean("dataSource", DataSource::class.java, Supplier { applicationSource })
                    .withBean(PostTokenAuthenticator::class.java, Supplier { PostTokenAuthenticator(LostSearchTestSecurity.KEY) })
                    .withPropertyValues(
                        "pawbridge.animal-lost-search.enabled=true",
                        "lost-search.python-url=" + stub.url(),
                        "python-ai-service.internal-api-key=" + KEY,
                        "spring.sql.init.mode=never",
                        "spring.jmx.enabled=false",
                    )
                    .run { context ->
                        check(context.startupFailure == null, "The actual reader, multipart controller and security chain must start")
                        emf.set(context.getBean("animalLostSearchEntityManagerFactory", EntityManagerFactory::class.java))
                        val manager = context.getBean("animalLostSearchTransactions", JpaTransactionManager::class.java)
                        manager.addListener(
                            object : TransactionExecutionListener {
                                override fun beforeBegin(transaction: TransactionExecution) {
                                    begins.incrementAndGet()
                                }

                                override fun afterBegin(transaction: TransactionExecution, failure: Throwable?) {
                                    check(
                                        failure == null && TransactionSynchronizationManager.isActualTransactionActive() &&
                                            TransactionSynchronizationManager.isCurrentTransactionReadOnly() &&
                                            TransactionSynchronizationManager.hasResource(applicationSource),
                                        "The actual post-inference transaction must bind a read-only reader connection",
                                    )
                                    val bound = JdbcTemplate(applicationSource)
                                    check(
                                        "on" == bound.queryForObject("SHOW transaction_read_only", String::class.java) &&
                                            pool().hikariPoolMXBean.activeConnections == 1,
                                        "PostgreSQL must observe read-only work on the single pooled connection",
                                    )
                                }
                            },
                        )
                        val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context)
                            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
                            .build()
                        try {
                            action.run(mvc, stub, begins)
                        } catch (fail: AssertionError) {
                            throw fail
                        } catch (fail: Exception) {
                            throw AssertionError("Owned photo request failed")
                        }
                    }
                check(emf.get() != null && !emf.get().isOpen, "Each request context must close its owned reader factory")
                stub.healthy()
                check(!pool().isClosed, "Context shutdown must not close the externally owned fixture pool")
                released()
            }
        }
    }

    private fun send(mvc: MockMvc, stub: PythonStub, species: String, resolved: Boolean, expected: Int): Response {
        released()
        stub.expectedBegins = stub.begins!!.get()
        stub.expectedBorrowed = applicationSource.borrowed.get()
        val response = mvc.perform(
            multipart(ROUTE)
                .file(MockMultipartFile("image", "private-original.jpg", "image/jpeg", byteArrayOf(1, 2, 3, 4)))
                .param("species", species)
                .param("lostDate", "2026-10-20")
                .param("region", "  synthetic region  ")
                .param("description", "  synthetic description  ")
                .param("includeAdoptedOrReturned", resolved.toString())
                .header("X-Internal-Api-Key", "untrusted-client-key")
                .header("X-User-Id", "99"),
        ).andReturn()
        check(response.response.status == expected, "The public multipart route must preserve its expected status")
        check(response.response.getHeader("Set-Cookie") == null, "Public inference must not create an authenticated session")
        stub.healthy()
        released()
        val body = response.response.getContentAsString(StandardCharsets.UTF_8)
        return Response(response, if (body.isBlank()) JSON.nodeFactory.nullNode() else JSON.readTree(body))
    }

    private class Reply(val status: Int, val body: ByteArray)

    private class Response(val result: MvcResult, val body: JsonNode)

    private inner class PythonStub : AutoCloseable {
        val server: HttpServer
        val executor: ExecutorService
        val reply = AtomicReference<Reply>()
        val failure = AtomicReference<AssertionError>()
        var begins: AtomicInteger? = null

        @Volatile
        var expectedBegins: Int = 0

        @Volatile
        var expectedBorrowed: Int = 0

        init {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            executor = Executors.newSingleThreadExecutor()
            server.executor = executor
            try {
                server.createContext(
                    "/internal/animals/lost-candidates",
                    HttpHandler { exchange ->
                        var bytes = "{}".toByteArray(StandardCharsets.UTF_8)
                        var status = 500
                        try {
                            check(
                                "POST" == exchange.requestMethod &&
                                    "/internal/animals/lost-candidates" == exchange.requestURI.path &&
                                    exchange.requestURI.rawQuery == null &&
                                    KEY == exchange.requestHeaders.getFirst("X-Internal-Api-Key"),
                                "The local Python request must use the configured key and exact POST",
                            )
                            check(
                                pool().hikariPoolMXBean.activeConnections == 0 && begins!!.get() == expectedBegins &&
                                    applicationSource.borrowed.get() == expectedBorrowed,
                                "Remote inference must precede every reader TX begin and connection acquisition",
                            )
                            val request = exchange.requestBody.readNBytes(8193)
                            check(request.size <= 8192, "The synthetic multipart request must remain bounded")
                            val body = String(request, StandardCharsets.ISO_8859_1).lowercase(Locale.ROOT)
                            check(
                                body.contains("filename=\"photo\"") && body.contains("application/octet-stream") &&
                                    !body.contains("private-original.jpg"),
                                "The native multipart client must use a fixed outbound filename and type",
                            )
                            val value = reply.get()
                            check(value != null, "A synthetic Python reply must be selected")
                            status = value.status
                            bytes = value.body
                        } catch (unexpected: Throwable) {
                            failure.compareAndSet(null, AssertionError("Owned Python boundary observation failed"))
                        }
                        try {
                            exchange.responseHeaders.set("Content-Type", "application/json")
                            exchange.sendResponseHeaders(status, bytes.size.toLong())
                            exchange.responseBody.write(bytes)
                        } finally {
                            exchange.close()
                        }
                    },
                )
                server.start()
            } catch (startFailure: Throwable) {
                server.stop(0)
                executor.shutdownNow()
                throw startFailure
            }
        }

        fun url(): String = "http://127.0.0.1:" + server.address.port

        fun healthy() {
            check(failure.get() == null, "The local Python boundary assertions must all pass")
        }

        override fun close() {
            try {
                server.stop(0)
            } finally {
                executor.shutdownNow()
                check(executor.awaitTermination(2, TimeUnit.SECONDS), "The local Python executor must terminate")
            }
        }
    }

    private class ObservedSource(delegate: DataSource) : DelegatingDataSource(delegate) {
        val borrowed = AtomicInteger()

        override fun getConnection(): Connection {
            borrowed.incrementAndGet()
            return super.getConnection()
        }

        override fun getConnection(user: String, password: String): Connection {
            borrowed.incrementAndGet()
            return super.getConnection(user, password)
        }
    }

    private fun fingerprint(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (table in listOf("animals", "shelters")) {
            result[table] = fixture.queryForObject(
                "SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(r)::text,',' ORDER BY id),'')) FROM pawbridge_animal." + table + " r",
                String::class.java,
            )!!
        }
        return result
    }

    private fun released() {
        check(
            !TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.hasResource(applicationSource) &&
                pool().hikariPoolMXBean.activeConnections == 0 && pool().hikariPoolMXBean.threadsAwaitingConnection == 0,
            "The owned reader must return every transaction and connection",
        )
    }

    private fun verifyIdentity(s: Settings) {
        val identity = fixture.queryForMap(
            "SELECT current_database() db,current_user actor,session_user session,current_setting('server_version_num') version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) owner",
        )
        check(
            s.database == identity["db"] && s.username == identity["actor"] && s.username == identity["session"] &&
                "170011" == identity["version"] && s.username != identity["owner"],
            "Only the new owned PostgreSQL reader is allowed",
        )
        val markers = fixture.query("SELECT marker FROM public.pawbridge_owned_fixture", RowMapper { row, _ -> row.getString(1) })
        check(markers.size == 1 && s.marker == markers.first(), "The exact owned fixture marker must match")
        check(
            true == fixture.queryForObject(
                "SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT pg_has_role(current_user,(SELECT datdba FROM pg_database WHERE datname=current_database()),'MEMBER') FROM pg_roles WHERE rolname=current_user",
                Boolean::class.javaObjectType,
            ),
            "The reader must not have administrative or owner membership",
        )
        for (table in listOf("animals", "shelters")) {
            check(
                true == fixture.queryForObject(
                    "SELECT has_table_privilege(current_user,?,'SELECT') AND NOT has_table_privilege(current_user,?,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT has_any_column_privilege(current_user,?,'INSERT,UPDATE,REFERENCES')",
                    Boolean::class.javaObjectType,
                    "pawbridge_animal." + table,
                    "pawbridge_animal." + table,
                    "pawbridge_animal." + table,
                ),
                "Fixture business tables must be SELECT-only",
            )
        }
        check(
            fixture.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind IN ('r','p','v','m','f') AND (has_table_privilege(current_user,c.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') OR has_any_column_privilege(current_user,c.oid,'INSERT,UPDATE,REFERENCES') OR ((has_table_privilege(current_user,c.oid,'SELECT') OR has_any_column_privilege(current_user,c.oid,'SELECT')) AND (n.nspname||'.'||c.relname) NOT IN ('pawbridge_animal.animals','pawbridge_animal.shelters','public.pawbridge_owned_fixture')))",
                Long::class.javaObjectType,
            ) == 0L,
            "The reader must have no writes or unrelated table reads",
        )
        check(
            fixture.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind='S' AND has_sequence_privilege(current_user,c.oid,'USAGE,SELECT,UPDATE')",
                Long::class.javaObjectType,
            ) == 0L,
            "The reader must have no sequence privileges",
        )
        for (schema in listOf("public", "pawbridge_animal", "pawbridge_user")) {
            check(
                false == fixture.queryForObject(
                    "SELECT has_schema_privilege(current_user,?,'CREATE')",
                    Boolean::class.javaObjectType,
                    schema,
                ),
                "The reader must not create schema objects",
            )
        }
        check(
            "on" == fixture.queryForObject("SHOW transaction_read_only", String::class.java),
            "The dedicated role must default to read-only",
        )
    }

    private fun interface Checked {
        fun run()
    }

    private fun interface HttpCheck {
        fun run(mvc: MockMvc, stub: PythonStub, begins: AtomicInteger)
    }

    private class Settings(
        val url: String,
        val database: String,
        val username: String,
        val password: String,
        val marker: String,
    ) {
        override fun toString(): String = "OwnedLostSearchSettings[redacted]"

        companion object {
            fun load(): Settings {
                val value = System.getenv("P10_PG_SETTINGS")
                check(value != null && value.isNotBlank(), "P10_PG_SETTINGS is required without skip")
                val p = Path.of(value)
                check(
                    p.isAbsolute && p.normalize() == p && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(p),
                    "Settings must be an absolute regular non-link file",
                )
                check(
                    Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS) ==
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE) &&
                        (Files.getAttribute(p, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1,
                    "Settings require 0600 and one hard link",
                )
                check(
                    System.getProperty("user.name") == Files.getOwner(p).name && Files.getOwner(p) == Files.getOwner(p.parent),
                    "Settings and parent must belong to this user",
                )
                var parent: Path? = p.parent
                while (parent != null) {
                    check(!Files.isSymbolicLink(parent), "Settings ancestors must not be links")
                    parent = parent.parent
                }
                val perms = Files.getPosixFilePermissions(p.parent)
                check(
                    !perms.contains(PosixFilePermission.GROUP_WRITE) && !perms.contains(PosixFilePermission.OTHERS_WRITE),
                    "Settings parent must reject other writers",
                )
                val props = Properties()
                Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS).use { input: InputStream -> props.load(input) }
                check(
                    props.stringPropertyNames() == setOf("jdbcUrl", "database", "username", "password", "marker"),
                    "Only the five dedicated reader settings are allowed",
                )
                val db = required(props, "database")
                val url = required(props, "jdbcUrl")
                val user = required(props, "username")
                val marker = required(props, "marker")
                check(
                    db == "pb_animal_command_owned_02c82195e3c7" && url == "jdbc:postgresql://127.0.0.1:52620/" + db &&
                        user == "pb_lost_search_reader" && marker.matches(Regex("[0-9a-f]{32}")),
                    "Only Root's exact new loopback fixture and reader are allowed",
                )
                return Settings(url, db, user, required(props, "password"), marker)
            }

            private fun required(props: Properties, key: String): String {
                val v = props.getProperty(key)
                check(v != null && v.isNotBlank(), "Every owned reader setting is required")
                return v
            }
        }
    }

    companion object {
        private const val ROUTE = "/api/v1/animals/lost-candidates"
        private const val KEY = "p10-public-synthetic-internal-key"
        private val JSON: ObjectMapper = ObjectMapper().findAndRegisterModules()
        private const val DOG_A = 990110L
        private const val DOG_B = 990111L
        private const val CAT = 990112L
        private const val CAT_ADOPTED = 990113L
        private const val DOG_ADOPTED = 990114L
        private const val ENDED = 990115L
        private const val MISSING = 990999L

        private fun ids(response: JsonNode): List<Long> {
            val values = ArrayList<Long>()
            response.path("candidates").forEach { c -> values.add(c.path("animal").path("id").asLong()) }
            return values
        }

        private fun candidate(id: Long, vararg evidence: String): Map<String, Any> {
            return mapOf("animalId" to id, "imageScore" to 0.7, "matchedEvidence" to evidence.toList())
        }

        private fun reply(status: Int, candidates: List<Map<String, Any>>): Reply {
            return Reply(status, JSON.writeValueAsBytes(mapOf("candidates" to candidates)))
        }

        private fun check(condition: Boolean, message: String) {
            if (!condition) {
                throw AssertionError(message)
            }
        }

        private fun observed(work: Checked) {
            try {
                work.run()
            } catch (fail: AssertionError) {
                throw fail
            } catch (fail: Throwable) {
                throw AssertionError("Owned lost-search observation failed")
            }
        }
    }
}
