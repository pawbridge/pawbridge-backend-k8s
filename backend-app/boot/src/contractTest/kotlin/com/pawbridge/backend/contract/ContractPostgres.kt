package com.pawbridge.backend.contract

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.HexFormat
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * The one PostgreSQL every contract test in the JVM talks to.
 *
 * The container starts on first use and is stopped by Testcontainers' Ryuk when the JVM ends. Starting it per
 * test class would repeat the slowest step of the suite, so nothing here may be an instance field of a test.
 *
 * Two roles exist: the container's superuser, used only through [admin] to seed rows, observe state and change
 * privileges, and [APP_ROLE], which the application connects with. The application role owns nothing,
 * so REVOKE and GRANT on it behave as they do for the production role.
 */
object ContractPostgres {
    const val APP_ROLE = "pawbridge_app"
    const val USER_SCHEMA = "pawbridge_user"
    const val COMMUNITY_SCHEMA = "pawbridge_community"
    const val ANIMAL_SCHEMA = "pawbridge_animal"

    /** The image the packaged-JAR smokes use in CI, pinned by digest. */
    const val DEFAULT_IMAGE =
        "postgres:17.11-bookworm@sha256:66aafa11cf15800a3c94763f7e11d1e7b2e37e5e84bc4ce027cb6e1e4bfaf4df"

    private const val IMAGE_PROPERTY = "pawbridge.contract.postgres-image"
    private const val IMAGE_ENVIRONMENT = "PAWBRIDGE_CONTRACT_POSTGRES_IMAGE"
    private const val DATABASE = "pawbridge_contract"
    private const val ADMIN_USER = "postgres"

    private val log = LoggerFactory.getLogger(ContractPostgres::class.java)

    /** Migration files per schema, in the order they must be applied. Each directory is copied from a service. */
    private val migrations = listOf(
        USER_SCHEMA to "schema/user-service",
        COMMUNITY_SCHEMA to "schema/community-service",
        ANIMAL_SCHEMA to "schema/animal-service",
    )

    /** Extensions the database owner provisions in public before the animal migrations (they check for them). */
    private val animalExtensions = listOf("vector", "pg_trgm")

    private val running: Running by lazy { start() }

    /** `jdbc:postgresql://host:port/database` without options: the application validates this shape. */
    val jdbcUrl: String get() = running.jdbcUrl

    val appPassword: String get() = running.appPassword

    /** Superuser access, one new connection per call. It never shares the application's pool. */
    val admin: JdbcTemplate get() = running.admin

    /** The Docker image the container was started from. */
    val image: String get() = running.image

    fun <T> adminConnection(body: (Connection) -> T): T {
        return DriverManager.getConnection(running.jdbcUrl, ADMIN_USER, running.adminPassword).use(body)
    }

    /**
     * Revokes [privilege] on [target] from the application role, runs [body], and always grants it back, even
     * when [body] fails. The pool keeps its connections: PostgreSQL checks privileges when a statement runs.
     *
     * Use the exact clause that was granted: a column privilege such as `UPDATE(token_hash)` only changes
     * anything when the role holds it per column.
     */
    fun <T> withRevoked(privilege: String, target: String, body: () -> T): T {
        admin.execute("REVOKE $privilege ON $target FROM $APP_ROLE")
        try {
            return body()
        } finally {
            admin.execute("GRANT $privilege ON $target TO $APP_ROLE")
        }
    }

    /** Every row of every table in the application schemas, so a test can prove it left no trace. */
    fun snapshot(): Map<String, String> {
        val tables = admin.queryForList(
            "SELECT schemaname || '.' || tablename FROM pg_tables WHERE schemaname IN (?, ?, ?) ORDER BY 1",
            String::class.java,
            USER_SCHEMA,
            COMMUNITY_SCHEMA,
            ANIMAL_SCHEMA,
        )
        return tables.associateWith { table ->
            admin.queryForObject(
                "SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text), '[]'::jsonb)::text FROM $table r",
                String::class.java,
            )!!
        }
    }

    private fun start(): Running {
        val image = configuredImage()
        log.info("Starting the contract PostgreSQL from image {}", image)
        val adminPassword = randomSecret()
        val appPassword = randomSecret()
        val container = PostgreSQLContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE)
            .withUsername(ADMIN_USER)
            .withPassword(adminPassword)
            // Durability is not what is tested; a RAM disk and no fsync keep the suite short.
            .withTmpFs(mapOf("/var/lib/postgresql/data" to "rw,nosuid,noexec,size=512m"))
            .withCommand(
                "postgres",
                "-c", "fsync=off",
                "-c", "synchronous_commit=off",
                "-c", "full_page_writes=off",
            )
        container.start()
        val jdbcUrl = "jdbc:postgresql://${container.host}:${container.firstMappedPort}/$DATABASE"
        // Pooled on purpose: one new TCP connection per seeding statement exhausts the ephemeral ports of the host
        // when the suite (or two suites) run back to back, which shows up as BindException in unrelated tests.
        val pool = HikariConfig()
        pool.jdbcUrl = jdbcUrl
        pool.username = ADMIN_USER
        pool.password = adminPassword
        pool.maximumPoolSize = 2
        pool.minimumIdle = 1
        pool.poolName = "contract-admin"
        val admin = JdbcTemplate(HikariDataSource(pool))
        val running = Running(jdbcUrl, adminPassword, appPassword, admin, image)
        prepareDatabase(running)
        return running
    }

    private fun prepareDatabase(running: Running) {
        DriverManager.getConnection(running.jdbcUrl, ADMIN_USER, running.adminPassword).use { connection ->
            for ((schema, directory) in migrations) {
                if (schema == ANIMAL_SCHEMA) {
                    // Fail the whole suite instead of skipping the schema: a missing schema would only surface later
                    // as confusing animal test failures, or hide them entirely.
                    check(provisionAnimalExtensions(connection)) {
                        "pgvector or pg_trgm is not available in this image; run with " +
                            "-PcontractPostgresImage=pawbridge-it/postgres:17.11-pgvector-prodmatch"
                    }
                }
                connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
                // The reviewed migrations name tables without a schema (V1-V8) or with it (V9 and later),
                // so every file runs with the owning schema first on the search path.
                connection.createStatement().use { it.execute("SET search_path TO $schema") }
                for (file in migrationFiles(directory)) {
                    connection.createStatement().use { it.execute(readResource("$directory/$file")) }
                }
            }
            connection.createStatement().use { it.execute("RESET search_path") }
            // The role is created after the migrations on purpose: user V4 grants table-wide privileges to
            // pawbridge_app when that role already exists, which would defeat the per-column revokes.
            connection.createStatement().use {
                it.execute(
                    "CREATE ROLE $APP_ROLE LOGIN PASSWORD '${running.appPassword}' " +
                        "NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS",
                )
            }
            connection.createStatement().use { it.execute(readResource("schema/app-grants.sql")) }
        }
    }

    /** True when every extension the animal migrations need is installed in public (the image must provide them). */
    private fun provisionAnimalExtensions(connection: Connection): Boolean {
        val available = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM pg_available_extensions").use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) else null }.toSet()
            }
        }
        if (!available.containsAll(animalExtensions)) {
            return false
        }
        for (extension in animalExtensions) {
            connection.createStatement().use { it.execute("CREATE EXTENSION IF NOT EXISTS $extension SCHEMA public") }
        }
        return true
    }

    /** `V<number>__name.sql` files of a directory sorted by version number (V10 after V9). */
    fun migrationFiles(directory: String): List<String> {
        val names = ClassPathResource(directory).file.list().orEmpty().filter { MIGRATION_NAME.matches(it) }
        return names.sortedBy { MIGRATION_NAME.matchEntire(it)!!.groupValues[1].toInt() }
    }

    private val MIGRATION_NAME = Regex("V([0-9]+)__.+\\.sql")

    private fun readResource(path: String): String {
        return ClassPathResource(path).inputStream.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
    }

    private fun configuredImage(): String {
        val fromProperty = System.getProperty(IMAGE_PROPERTY)
        if (!fromProperty.isNullOrBlank()) {
            return fromProperty
        }
        val fromEnvironment = System.getenv(IMAGE_ENVIRONMENT)
        if (!fromEnvironment.isNullOrBlank()) {
            return fromEnvironment
        }
        return DEFAULT_IMAGE
    }

    private fun randomSecret(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return HexFormat.of().formatHex(bytes)
    }

    private class Running(
        val jdbcUrl: String,
        val adminPassword: String,
        val appPassword: String,
        val admin: JdbcTemplate,
        val image: String,
    )
}
