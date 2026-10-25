package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGalleryFeed
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGallerySnapshots
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryFeedController
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryPagesController
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.AbstractDataSource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.TransactionDefinition
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
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.function.Function

/** Actual SQL and physical reader boundaries on the new SELECT-only P16 fixture; no DML or object transfer. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class OwnedPostgresqlAnimalGalleryFeedTest {
    private lateinit var settings: Settings
    private var pool: HikariDataSource? = null
    private var reader: ReaderSource? = null
    private lateinit var jdbc: JdbcTemplate
    private lateinit var reads: TransactionTemplate
    private lateinit var streams: TransactionTemplate
    private lateinit var probe: JdbcTemplate
    private lateinit var baseline: Map<String, Fingerprint>
    private val observations: MutableList<Sample> = ArrayList()

    private fun reader(): ReaderSource = reader!!

    @BeforeAll
    fun requireNewOwnedContainerReaderAndTwoImmutableTablesBeforeApplicationBorrow() {
        observed("P16 isolated identity") {
            settings = Settings.load()
            val direct = DriverManagerDataSource(settings.url, settings.username, settings.password)
            val timeouts = Properties()
            timeouts.setProperty("connectTimeout", "3")
            timeouts.setProperty("socketTimeout", "5")
            direct.setConnectionProperties(timeouts)
            probe = JdbcTemplate(direct)
            probe.queryTimeout = 5
            verifyServer()
            val config = HikariConfig()
            config.jdbcUrl = settings.url
            config.username = settings.username
            config.password = settings.password
            config.poolName = "owned-p16-gallery-reader"
            config.maximumPoolSize = 2
            config.minimumIdle = 0
            config.isReadOnly = true
            config.connectionTimeout = 2000
            config.validationTimeout = 1000
            config.initializationFailTimeout = -1
            config.addDataSourceProperty("connectTimeout", "3")
            config.addDataSourceProperty("socketTimeout", "10")
            pool = HikariDataSource(config)
            val source = ReaderSource()
            reader = source
            jdbc = JdbcTemplate(source)
            jdbc.queryTimeout = 3
            val manager = JdbcTransactionManager(source)
            reads = TransactionTemplate(manager)
            reads.isReadOnly = true
            reads.timeout = 120
            streams = TransactionTemplate(manager, reads)
            streams.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            baseline = fingerprint()
            check(
                baseline["pawbridge_animal.animals"]!!.count == 9L &&
                    baseline["pawbridge_animal.apms_photo_archive"]!!.count == 8L &&
                    baseline["public.pb_gallery_feed_fixture"]!!.count == 1L,
                "The exact two-source and single-marker seed counts are mandatory",
            )
            released()
        }
    }

    @BeforeEach
    fun requireUnchangedInputAndClearOnlyIdleCounters() {
        observed("P16 before") {
            released()
            check(fingerprint() == baseline, "Both input tables must retain the initial fixture")
            reader().reset()
        }
    }

    @AfterEach
    fun preserveEveryInputAndReturnAllReaderResources() {
        observed("P16 after") {
            released()
            check(fingerprint() == baseline, "No contract may change either input table")
            observations.addAll(reader().samples)
        }
    }

    @AfterAll
    fun closeOnlyTheOwnedIdlePoolAndReportActualJdbcSettingsWithoutSqlOrUrls() {
        val source = pool
        if (source != null) {
            released()
            source.close()
            check(source.isClosed, "The test-owned reader pool must close")
            println(
                "P16_JDBC_OBSERVATION finalTimeoutSeconds=" + observations.map { sample -> sample.timeout }.distinct().sorted() +
                    " fetchSizes=" + observations.map { sample -> sample.fetch }.distinct().sorted(),
            )
        }
    }

    @Test
    fun givenSelectOnlyInputs__whenStreamFailsAndRecovers__thenUsePhysicalReadOnlyRepeatableReadAndReturnAllResources() {
        observed("P16 SQL and recovery") {
            val feed = feed(signer(AtomicInteger()))
            val ids = ArrayList<Long>()
            feed.streamEntries(
                Consumer { entry ->
                    check(
                        TransactionSynchronizationManager.isActualTransactionActive() &&
                            TransactionSynchronizationManager.isCurrentTransactionReadOnly() &&
                            TransactionSynchronizationManager.hasResource(reader()),
                        "Source rows must be consumed in the bound reader transaction",
                    )
                    ids.add((entry.record["id"] as Number).toLong())
                    val photo: JsonNode = JSON.valueToTree(entry)
                    val id = (entry.record["id"] as Number).toLong()
                    check(IDS.contains(id), "READY/slot/current-source/species filters must precede publication")
                    check(
                        entry.record["species"] == (if (id == 996102L) "CAT" else "DOG") &&
                            entry.record["status"] == (if (id == 996102L) "ADOPTED" else "NOTICE"),
                        "Actual species and unfiltered status metadata must survive mapping",
                    )
                    check(
                        "2026-01-02" == entry.record["happen_date"] && "Owned gallery place" == entry.record["happen_place"] &&
                            "brown" == entry.record["color"] && "Owned gallery mark" == entry.record["special_mark"] &&
                            "Owned gallery description" == entry.record["description"],
                        "All six selected metadata fields must retain their fixture values",
                    )
                    val expected = if (id == 996102L) B else A
                    check(
                        expected == entry.record["source_sha256"] && expected == photo.path("photo").path("sha256").asText(),
                        "Record and archived photo digests must agree",
                    )
                    check(
                        photo.path("photo").path("key").asText() == "apms/photos/" + expected + ".jpg",
                        "Only the verified archived object key may enter the feed",
                    )
                    check(
                        photo.path("photo").path("bytes").asLong() == (if (id == 996102L) 200L else 100L),
                        "Stored length must survive actual SQL mapping",
                    )
                    check(photo.path("photo").path("mime").asText() == "image/jpeg", "Stored MIME must survive actual SQL mapping")
                },
            )
            check(ids == IDS, "Actual SQL must return exactly the two eligible animals ordered by ID")
            requireReads("repeatable read", 1)
            released()
            observations.addAll(reader().samples)
            reader().reset()
            expect(SyntheticFailure::class.java) { feed.streamEntries(Consumer { throw SyntheticFailure() }) }
            requireReads("repeatable read", 1)
            released()
            observations.addAll(reader().samples)
            reader().reset()
            val recovered = ArrayList<Long>()
            feed.streamEntries(Consumer { entry -> recovered.add((entry.record["id"] as Number).toLong()) })
            check(recovered == IDS, "A fresh stream after consumer failure must return the complete unchanged source")
            requireReads("repeatable read", 1)
            released()
        }
    }

    @Test
    fun givenActualReaderAndNewSnapshotDirectory__whenV1AndV2Run__thenSignOnlyAfterReadAndKeepPageLifecycleDatabaseFree(
        @TempDir directory: Path,
    ) {
        observed("P16 phase boundaries") {
            val signed = AtomicInteger()
            val feed = feed(signer(signed))
            val first = feed.snapshot(null)
            val body = JSON.readTree(first.body!!)
            check(body.path("complete").asBoolean() && body.path("count").asInt() == 2, "The complete legacy body must retain its two rows")
            check(
                body.path("photos").size() == 2 && signed.get() == 2,
                "The actual two-photo inventory must be signed only after its read closes",
            )
            check(
                body.path("records").get(1).path("status").asText() == "ADOPTED",
                "Feed eligibility must not silently add the live-search status filter",
            )
            requireReads("read committed", 1)
            released()
            val calls = signed.get()
            val unchanged = feed.snapshot(first.etag)
            check(
                unchanged.body == null && first.etag == unchanged.etag && signed.get() == calls,
                "Unchanged content must retain ETag and skip every signing call",
            )
            check(reader().borrowed.get() == 2, "An ETag comparison still needs its actual source read")
            released()
            observations.addAll(reader().samples)
            reader().reset()
            val consumed = AtomicInteger()
            LostGallerySnapshots(
                JSON,
                Consumer<Consumer<LostGallerySnapshots.Entry>> { consumer ->
                    feed.streamEntries(
                        Consumer { entry ->
                            check(
                                TransactionSynchronizationManager.isActualTransactionActive() &&
                                    TransactionSynchronizationManager.hasResource(reader()),
                                "Snapshot rows must be written while the explicit source reader is bound",
                            )
                            consumed.incrementAndGet()
                            consumer.accept(entry)
                        },
                    )
                },
                signer(signed),
                directory.resolve("new-p16-snapshots"),
                Clock.systemUTC(),
            ).use { snapshots ->
                val descriptor = snapshots.create(REQUEST)
                check(
                    consumed.get() == 2 && descriptor.count == 2 && signed.get() == calls,
                    "Creating an immutable file must consume the source without signing or publishing a gallery",
                )
                requireReads("repeatable read", 1)
                released()
                observations.addAll(reader().samples)
                reader().reset()
                val described = snapshots.describe(descriptor.snapshotId)
                check(
                    described.snapshotSha256 == descriptor.snapshotSha256,
                    "Describe must preserve the immutable snapshot identity",
                )
                val ids = ArrayList<Long>()
                var cursor: String? = descriptor.cursor
                do {
                    val page = JSON.readTree(snapshots.page(descriptor.snapshotId, cursor, 1))
                    for (item in page.path("items")) {
                        ids.add(item.path("record").path("id").asLong())
                    }
                    check(ids.size <= IDS.size, "Snapshot pages must not exceed the complete source count")
                    cursor = if (page.path("nextCursor").isNull) null else page.path("nextCursor").asText()
                    check(page.path("complete").asBoolean() == (ids.size == 2), "The final page must agree with the declared source count")
                } while (cursor != null)
                check(ids == IDS, "Snapshot pages must retain actual SQL ordering without duplication")
                snapshots.release(descriptor.snapshotId)
                expect(LostGallerySnapshots.Gone::class.java) { snapshots.describe(descriptor.snapshotId) }
                check(
                    reader().borrowed.get() == 0 && consumed.get() == 2,
                    "Describe, signing, page and release must never reopen the source",
                )
                released()
            }
        }
    }

    @Test
    fun givenProductionControllersAndNewDirectory__whenAllFiveWireRoutesRun__thenAuthenticateBeforeDatabaseSigningOrSourceWork(
        @TempDir directory: Path,
    ) {
        observed("P16 five controller routes") {
            val signed = AtomicInteger()
            val sources = AtomicInteger()
            val feed = feed(signer(signed))
            LostGallerySnapshots(
                JSON,
                Consumer<Consumer<LostGallerySnapshots.Entry>> { consumer ->
                    sources.incrementAndGet()
                    feed.streamEntries(consumer)
                },
                signer(signed),
                directory.resolve("new-p16-wire"),
                Clock.systemUTC(),
            ).use { snapshots ->
                val mvc: MockMvc = MockMvcBuilders.standaloneSetup(
                    LostGalleryFeedController(feed, KEY),
                    LostGalleryPagesController(snapshots, KEY),
                ).build()
                val absent = "d".repeat(32)
                val files = fileCount(directory)
                val routes = listOf(
                    Route("GET", ROOT),
                    Route("POST", ROOT + "/snapshots"),
                    Route("GET", ROOT + "/snapshots/" + absent),
                    Route("GET", ROOT + "/snapshots/" + absent + "/pages"),
                    Route("DELETE", ROOT + "/snapshots/" + absent),
                )
                for (route in routes) {
                    for (key in arrayOf<String?>(null, "wrong", KEY + " ")) {
                        exchange(mvc, route.method, route.path, key, mapOf("cursor" to "untrusted", "limit" to "1"), 401)
                    }
                }
                check(
                    reader().borrowed.get() == 0 && sources.get() == 0 && signed.get() == 0 && fileCount(directory) == files,
                    "Missing, wrong and non-exact keys on all five routes must precede DB, signer and file-source work",
                )
                val legacy = exchange(mvc, "GET", ROOT, KEY, mapOf(), 200)
                check(
                    legacy.body.path("count").asInt() == 2 && "no-store" == legacy.cache,
                    "Legacy wire response must be complete and non-cacheable",
                )
                released()
                val beforeConditional = signed.get()
                val unchanged = mvc.perform(
                    MockMvcRequestBuilders.get(ROOT)
                        .header("X-Internal-Api-Key", KEY)
                        .header("If-None-Match", legacy.etag),
                ).andReturn().response
                check(
                    unchanged.status == 304 && unchanged.contentAsByteArray.size == 0 &&
                        legacy.etag == unchanged.getHeader("ETag") && signed.get() == beforeConditional,
                    "Actual conditional GET must return304 without any additional signing",
                )
                released()
                val created = exchange(mvc, "POST", ROOT + "/snapshots", KEY, mapOf(), 200)
                val id = created.body.path("snapshotId").asText()
                val cursor = created.body.path("cursor").asText()
                check(
                    created.body.path("protocol").asText() == "pawbridge-gallery-pages-v2" && created.body.path("count").asInt() == 2,
                    "The existing v2 descriptor wire contract must remain unchanged",
                )
                val borrows = reader().borrowed.get()
                val described = exchange(mvc, "GET", ROOT + "/snapshots/" + id, KEY, mapOf(), 200)
                check(
                    described.body.path("snapshotSha256") == created.body.path("snapshotSha256"),
                    "Wire describe must retain the issued snapshot fingerprint",
                )
                val first = exchange(
                    mvc, "GET", ROOT + "/snapshots/" + id + "/pages", KEY, mapOf("cursor" to cursor, "limit" to "1"), 200,
                )
                check(
                    first.body.path("start").asInt() == 0 && first.body.path("items").size() == 1 &&
                        !first.body.path("complete").asBoolean(),
                    "The first wire page must retain offset, limit and completion semantics",
                )
                val last = exchange(
                    mvc,
                    "GET",
                    ROOT + "/snapshots/" + id + "/pages",
                    KEY,
                    mapOf("cursor" to first.body.path("nextCursor").asText(), "limit" to "1"),
                    200,
                )
                check(
                    last.body.path("start").asInt() == 1 && last.body.path("complete").asBoolean() &&
                        last.body.path("nextCursor").isNull,
                    "The terminal wire page must complete the same snapshot",
                )
                exchange(mvc, "DELETE", ROOT + "/snapshots/" + id, KEY, mapOf(), 204)
                exchange(mvc, "GET", ROOT + "/snapshots/" + id, KEY, mapOf(), 410)
                check(
                    reader().borrowed.get() == borrows && sources.get() == 1,
                    "Wire page/describe/release must not consume another source or DB connection",
                )
                released()
            }
        }
    }

    private fun feed(signer: Function<String, String>): LostGalleryFeed {
        return LostGalleryFeed(jdbc, JSON, signer, reads, streams)
    }

    private fun signer(calls: AtomicInteger): Function<String, String> {
        return Function { key ->
            released()
            check(key.matches(Regex("apms/photos/[a-f0-9]{64}\\.(jpg|png|webp)")), "The signer may receive only verified archive keys")
            calls.incrementAndGet()
            "https://owned-p16.invalid/pawbridge-animal-originals/" + key
        }
    }

    private fun requireReads(isolation: String, expectedBorrows: Int) {
        val source = reader()
        check(
            source.borrowed.get() == expectedBorrows && source.samples.isNotEmpty(),
            "One source read must use the expected number of physical borrows",
        )
        check(
            source.samples.all { s -> s.readOnly && isolation == s.isolation && s.bound && s.manualCommit },
            "Actual PostgreSQL transaction mode and same-reader binding must match the selected phase",
        )
        check(
            source.samples.map { sample -> sample.pid }.distinct().size == 1,
            "Conflict and source SQL must share the same PostgreSQL backend",
        )
        check(
            source.samples.any { sample -> sample.rows } && source.samples.filter { sample -> sample.rows }.all { s -> s.fetch == 200 },
            "Actual source statements must keep PostgreSQL cursor fetch size200",
        )
        check(source.timeoutRequests.contains(20), "The original setQueryTimeout20 call must remain present")
        check(
            source.samples.all { s -> s.timeout > 0 && s.timeout <= 120 },
            "Final timeout must be observed within the supplied transaction budget, not assumed to equal20",
        )
    }

    private fun released() {
        val source = reader
        val ownedPool = pool
        check(
            !TransactionSynchronizationManager.isActualTransactionActive() &&
                (source == null || !TransactionSynchronizationManager.hasResource(source)) &&
                (ownedPool == null || !TransactionSynchronizationManager.hasResource(ownedPool)),
            "No caller transaction or reader binding may escape a phase",
        )
        if (source != null) {
            check(
                source.borrowed.get() == source.returned.get() && source.statements.get() == source.closedStatements.get() &&
                    source.results.get() == source.closedResults.get(),
                "Every borrowed connection, statement and result cursor must be returned",
            )
        }
        if (ownedPool != null) {
            check(
                ownedPool.hikariPoolMXBean.activeConnections == 0 && ownedPool.hikariPoolMXBean.threadsAwaitingConnection == 0,
                "The test-owned reader pool must have no active or waiting borrower",
            )
        }
    }

    private fun fingerprint(): Map<String, Fingerprint> {
        val result = LinkedHashMap<String, Fingerprint>()
        TABLES.forEach { (table, key) ->
            val row = probe.queryForMap(
                "SELECT count(*) AS n,md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY " + key + "),'')) AS digest FROM " + table + " t",
            )
            result[table] = Fingerprint((row["n"] as Number).toLong(), row["digest"] as String)
        }
        return result
    }

    private fun verifyServer() {
        val id = probe.queryForMap(
            "SELECT current_database() AS db,current_user AS actor,session_user AS session,current_setting('server_version_num') AS version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) AS owner",
        )
        check(
            settings.database == id["db"] && settings.username == id["actor"] && settings.username == id["session"] &&
                "170011" == id["version"] && settings.username != id["owner"],
            "Only the isolated PostgreSQL17.11 database and separate reader are accepted",
        )
        val markers = probe.query("SELECT marker FROM public.pb_gallery_feed_fixture", RowMapper { r, _ -> r.getString(1) })
        check(markers.size == 1 && settings.marker == markers.first(), "The exact single P16 marker is mandatory")
        check(
            true == probe.queryForObject(
                "SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit AND NOT EXISTS (SELECT 1 FROM pg_auth_members m WHERE m.member=pg_roles.oid) AND NOT pg_has_role(current_user,(SELECT datdba FROM pg_database WHERE datname=current_database()),'MEMBER') FROM pg_roles WHERE rolname=current_user",
                Boolean::class.javaObjectType,
            ),
            "The reader must have no owner or administrator inheritance",
        )
        for (schema in listOf("public", "pawbridge_animal")) {
            check(
                false == probe.queryForObject(
                    "SELECT has_schema_privilege(current_user,?,'CREATE')",
                    Boolean::class.javaObjectType,
                    schema,
                ),
                "The reader may not create schema objects",
            )
        }
        check(
            false == probe.queryForObject(
                "SELECT has_database_privilege(current_user,current_database(),'CREATE,TEMP')",
                Boolean::class.javaObjectType,
            ),
            "The reader must have no database or temporary-object creation privilege",
        )
        for (table in TABLES.keys) {
            check(
                true == probe.queryForObject(
                    "SELECT has_table_privilege(current_user,?,'SELECT') AND NOT has_table_privilege(current_user,?,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')",
                    Boolean::class.javaObjectType,
                    table,
                    table,
                ),
                "Only SELECT may be granted on both source tables",
            )
        }
        check(
            probe.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind IN('r','p','v','m','f') AND (has_table_privilege(current_user,c.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') OR has_any_column_privilege(current_user,c.oid,'INSERT,UPDATE,REFERENCES') OR ((has_table_privilege(current_user,c.oid,'SELECT') OR has_any_column_privilege(current_user,c.oid,'SELECT')) AND (n.nspname||'.'||c.relname) NOT IN ('public.pb_gallery_feed_fixture','pawbridge_animal.animals','pawbridge_animal.apms_photo_archive')))",
                Long::class.javaObjectType,
            ) == 0L,
            "The reader must have no application writes or unrelated application reads",
        )
        check(
            false == probe.queryForObject(
                "SELECT has_table_privilege(current_user,'pawbridge_animal.shelters','SELECT') OR has_any_column_privilege(current_user,'pawbridge_animal.shelters','SELECT')",
                Boolean::class.javaObjectType,
            ),
            "The shelter FK seed table must remain unreadable by the runtime reader",
        )
        check(
            probe.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind='S' AND has_sequence_privilege(current_user,c.oid,'USAGE,SELECT,UPDATE')",
                Long::class.javaObjectType,
            ) == 0L,
            "The reader must have no application sequence privilege",
        )
        val tables = probe.query(
            "SELECT tablename FROM pg_tables WHERE schemaname='pawbridge_animal' ORDER BY tablename",
            RowMapper { r, _ -> r.getString(1) },
        )
        check(
            tables == listOf("animals", "apms_photo_archive", "shelters"),
            "P16 must preserve the original shelter FK table and only the two selected source tables",
        )
    }

    private inner class ReaderSource : AbstractDataSource() {
        val borrowed = AtomicInteger()
        val returned = AtomicInteger()
        val statements = AtomicInteger()
        val closedStatements = AtomicInteger()
        val results = AtomicInteger()
        val closedResults = AtomicInteger()
        val samples: MutableList<Sample> = ArrayList()
        val timeoutRequests: MutableList<Int> = ArrayList()

        override fun getConnection(): Connection {
            val connection = pool!!.connection
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
                    if (method.name == "prepareStatement" && value is PreparedStatement && args!![0] is String) {
                        return@InvocationHandler statement(connection, value, args[0] as String)
                    }
                    value
                },
            ) as Connection
        }

        override fun getConnection(username: String, password: String): Connection {
            throw SQLException("Explicit credential override is not supported")
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
                    if (method.name == "setQueryTimeout") {
                        timeoutRequests.add(args!![0] as Int)
                    }
                    if (method.name == "executeQuery" && sql.contains("pawbridge_animal.apms_photo_archive")) {
                        connection.createStatement().use { inspection ->
                            inspection.executeQuery(
                                "SELECT current_setting('transaction_read_only'),current_setting('transaction_isolation'),pg_backend_pid()",
                            ).use { row ->
                                check(row.next(), "Physical reader settings must be observable on the actual borrowed connection")
                                samples.add(
                                    Sample(
                                        "on" == row.getString(1),
                                        row.getString(2),
                                        row.getInt(3),
                                        statement.fetchSize,
                                        statement.queryTimeout,
                                        !sql.startsWith("SELECT stored_sha256"),
                                        TransactionSynchronizationManager.hasResource(this@ReaderSource),
                                        !connection.autoCommit,
                                    ),
                                )
                            }
                        }
                    }
                    val value = call(statement, method, args)
                    if (value is ResultSet) {
                        return@InvocationHandler result(value)
                    }
                    value
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

        fun reset() {
            released()
            borrowed.set(0)
            returned.set(0)
            statements.set(0)
            closedStatements.set(0)
            results.set(0)
            closedResults.set(0)
            samples.clear()
            timeoutRequests.clear()
        }
    }

    private fun interface Checked {
        fun run()
    }

    private class SyntheticFailure : RuntimeException()

    private class Fingerprint(val count: Long, val digest: String) {
        override fun equals(other: Any?): Boolean {
            return other is Fingerprint && count == other.count && digest == other.digest
        }

        override fun hashCode(): Int = 31 * count.hashCode() + digest.hashCode()
    }

    private class HttpResult(val body: JsonNode, val cache: String?, val etag: String?)

    private class Route(val method: String, val path: String)

    private class Sample(
        val readOnly: Boolean,
        val isolation: String,
        val pid: Int,
        val fetch: Int,
        val timeout: Int,
        val rows: Boolean,
        val bound: Boolean,
        val manualCommit: Boolean,
    )

    private class Settings(
        val url: String,
        val database: String,
        val username: String,
        val password: String,
        val marker: String,
    ) {
        override fun toString(): String = "OwnedGallerySettings[redacted]"

        companion object {
            fun load(): Settings {
                val value = System.getenv("P16_PG_SETTINGS")
                check(value != null && value.isNotBlank(), "P16_PG_SETTINGS is mandatory and never causes a skip")
                val file = Path.of(value)
                check(
                    file.isAbsolute && file.normalize() == file && file.fileName.toString() == "gallery-feed.properties" &&
                        Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file),
                    "Only the exact absolute P16 settings file is accepted",
                )
                check(
                    Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS) ==
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE) &&
                        (Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1,
                    "P16 settings must have0600 and one hard link",
                )
                check(
                    System.getProperty("user.name") == Files.getOwner(file).name &&
                        Files.getOwner(file) == Files.getOwner(file.parent),
                    "Settings and parent must belong to the current process owner",
                )
                var parent: Path? = file.parent
                while (parent != null) {
                    check(!Files.isSymbolicLink(parent), "Settings ancestors cannot be symbolic links")
                    parent = parent.parent
                }
                val permissions = Files.getPosixFilePermissions(file.parent, LinkOption.NOFOLLOW_LINKS)
                check(
                    permissions == setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
                    "P16 settings parent must retain exact0700 permissions",
                )
                val values = Properties()
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input: InputStream -> values.load(input) }
                val database = required(values, "database")
                val url = required(values, "jdbcUrl")
                val username = required(values, "username")
                val marker = required(values, "marker")
                check(
                    database.matches(Regex("pb_gallery_feed_owned_[0-9a-f]{12}")) &&
                        url == "jdbc:postgresql://127.0.0.1:52620/" + database,
                    "Only the exact isolated P16 loopback database is accepted",
                )
                check(
                    username == "pb_gallery_feed_reader" && marker.matches(Regex("[0-9a-f]{32}")),
                    "Owned reader and marker identities are mandatory",
                )
                check(
                    values.stringPropertyNames() == setOf("jdbcUrl", "username", "password", "database", "marker"),
                    "P16 settings must retain exactly the five approved fields",
                )
                return Settings(url, database, username, required(values, "password"), marker)
            }

            private fun required(values: Properties, key: String): String {
                val result = values.getProperty(key)
                check(result != null && result.isNotBlank(), "Every owned setting is mandatory")
                return result
            }
        }
    }

    companion object {
        private const val ROOT = "/internal/animals/lost-gallery"
        private const val KEY = "owned-p16-synthetic-internal-key"
        private val REQUEST: String = "c".repeat(32)
        private val A: String = "a".repeat(64)
        private val B: String = "b".repeat(64)
        private val IDS: List<Long> = listOf(996101L, 996102L)
        private val JSON: ObjectMapper = ObjectMapper().findAndRegisterModules()
        private val TABLES: Map<String, String> = mapOf(
            "pawbridge_animal.animals" to "id",
            "pawbridge_animal.apms_photo_archive" to "animal_id,slot",
            "public.pb_gallery_feed_fixture" to "marker",
        )

        private fun exchange(
            mvc: MockMvc,
            method: String,
            path: String,
            key: String?,
            params: Map<String, String>,
            expected: Int,
        ): HttpResult {
            val request: MockHttpServletRequestBuilder = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path)
                .accept(MediaType.APPLICATION_JSON)
            if (key != null) {
                request.header("X-Internal-Api-Key", key)
            }
            if (method == "POST") {
                request.header("X-Gallery-Request-Id", REQUEST)
            }
            params.forEach { (name, value) -> request.param(name, value) }
            val response = mvc.perform(request).andReturn().response
            check(response.status == expected, "Unexpected owned gallery wire status")
            val bytes = response.contentAsByteArray
            return HttpResult(
                if (bytes.size == 0) JSON.nodeFactory.nullNode() else JSON.readTree(bytes),
                response.getHeader("Cache-Control"),
                response.getHeader("ETag"),
            )
        }

        private fun fileCount(root: Path): Long {
            Files.walk(root).use { files -> return files.filter { path -> Files.isRegularFile(path) }.count() }
        }

        private fun call(target: Any, method: Method, args: Array<Any?>?): Any? {
            try {
                return if (args == null) method.invoke(target) else method.invoke(target, *args)
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
                throw AssertionError("Owned gallery observation failed: " + phase + "; type=" + failure.javaClass.name)
            }
        }

        private fun expect(type: Class<out Throwable>, action: Checked) {
            try {
                action.run()
            } catch (failure: Throwable) {
                check(type.isInstance(failure), "Unexpected owned synthetic failure type")
                return
            }
            throw AssertionError("Expected owned synthetic failure was absent")
        }

        private fun check(value: Boolean, fixed: String) {
            if (!value) {
                throw AssertionError(fixed)
            }
        }
    }
}
