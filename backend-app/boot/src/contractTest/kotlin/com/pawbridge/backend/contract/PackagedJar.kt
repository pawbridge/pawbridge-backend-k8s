package com.pawbridge.backend.contract

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Starts the real `bootJar` output as a child process, the way an operator runs it: `java -jar`, configuration
 * only from environment variables, console output in a file.
 *
 * Every value is synthetic. The database is the contract PostgreSQL (application role), the object storage is
 * [ContractFakeS3], mail points at a loopback address nobody listens on, and every scheduler is switched off.
 */
object PackagedJar {
    /** Names of the settings without which the JAR must refuse to start. */
    val REQUIRED = listOf(
        "PAWBRIDGE_DB_URL",
        "PAWBRIDGE_DB_USER",
        "PAWBRIDGE_DB_PASSWORD",
        "PAWBRIDGE_JWT_SECRET",
        "PAWBRIDGE_STORAGE_ENDPOINT",
        "PAWBRIDGE_STORAGE_BUCKET",
        "PAWBRIDGE_STORAGE_PUBLIC_BASE_URL",
        "PAWBRIDGE_STORAGE_ACCESS_KEY_ID",
        "PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY",
        "PAWBRIDGE_STORAGE_REGION",
    )

    const val MAIL_PASSWORD = "synthetic-mail-password"

    /** Switches every scheduled job off explicitly, whatever its default is. */
    private val ARGUMENTS = listOf(
        "--spring.main.banner-mode=off",
        "--pawbridge.search.rebuild-enabled=false",
        "--apms.batch.schedule-enabled=false",
        "--shelter-directory.schedule-enabled=false",
        "--pawbridge.shelter-observations.schedule-enabled=false",
        "--pawbridge.animal-query.projection-schedule-enabled=false",
        "--pawbridge.visits.collection-enabled=false",
    )

    /** The `bootJar` file the Gradle task passed in; the test task depends on `:boot:bootJar`, so it is fresh. */
    val jar: Path by lazy {
        val path = Path.of(requireNotNull(System.getProperty("pawbridge.contract.jar")) { "pawbridge.contract.jar is not set" })
        check(Files.isRegularFile(path)) { "The packaged JAR does not exist: ${path.fileName}" }
        path
    }

    /** One running or finished child with its console output file. */
    class Launched(val process: Process, val log: File) {
        fun output(): String = log.readText()
    }

    /** The complete valid configuration of one child. [jwtSecret] signs the tokens the test sends. */
    fun environment(serverPort: Int, managementPort: Int, jwtSecret: String): Map<String, String> {
        val inherited = listOf("PATH", "JAVA_HOME", "LANG", "LC_ALL", "HOME", "TMPDIR")
            .mapNotNull { name -> System.getenv(name)?.let { name to it } }
        return inherited.toMap() + mapOf(
            "PAWBRIDGE_DB_URL" to ContractPostgres.jdbcUrl,
            "PAWBRIDGE_DB_USER" to ContractPostgres.APP_ROLE,
            "PAWBRIDGE_DB_PASSWORD" to ContractPostgres.appPassword,
            "PAWBRIDGE_DB_POOL_MAX_SIZE" to "4",
            "PAWBRIDGE_SERVER_PORT" to serverPort.toString(),
            "PAWBRIDGE_MANAGEMENT_PORT" to managementPort.toString(),
            "PAWBRIDGE_MANAGEMENT_ADDRESS" to "127.0.0.1",
            "PAWBRIDGE_JWT_SECRET" to jwtSecret,
            "PAWBRIDGE_STORAGE_ENDPOINT" to ContractFakeS3.endpoint,
            "PAWBRIDGE_STORAGE_BUCKET" to ContractFakeS3.BUCKET,
            "PAWBRIDGE_STORAGE_PUBLIC_BASE_URL" to ContractFakeS3.PUBLIC_BASE_URL,
            "PAWBRIDGE_STORAGE_OBJECT_PREFIX" to ContractFakeS3.OBJECT_PREFIX,
            "PAWBRIDGE_STORAGE_ACCESS_KEY_ID" to ContractFakeS3.ACCESS_KEY,
            "PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY" to ContractFakeS3.SECRET_KEY,
            "PAWBRIDGE_STORAGE_REGION" to "us-east-1",
            "SPRING_MAIL_HOST" to "127.0.0.1",
            "SPRING_MAIL_PORT" to "9",
            "SPRING_MAIL_USERNAME" to "synthetic-mail-user",
            "SPRING_MAIL_PASSWORD" to MAIL_PASSWORD,
        )
    }

    /** Runs `java -jar` with exactly [environment] (nothing else is inherited) and the console in a new file. */
    fun launch(environment: Map<String, String>, directory: Path, name: String): Launched {
        val log = Files.createTempFile(directory, name, ".log").toFile()
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command = listOf(java, "-Xmx256m", "-Xss512k", "-jar", jar.toString()) + ARGUMENTS
        val builder = ProcessBuilder(command)
        builder.environment().clear()
        builder.environment().putAll(environment)
        builder.redirectErrorStream(true)
        builder.redirectOutput(log)
        return Launched(builder.start(), log)
    }

    /** A free loopback port. The window between closing the socket and the child binding it is accepted. */
    fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    /** Ends a child that is still running, for cleanup after a failed test. */
    fun kill(launched: Launched?) {
        val process = launched?.process ?: return
        if (process.isAlive) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }
}
