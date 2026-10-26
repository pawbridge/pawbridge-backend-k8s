package com.pawbridge.backend.contract

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * The real `bootJar` output must refuse to start when a required setting is missing or malformed: a non-zero exit,
 * a message that names the setting but never a value, and no listener, no database connection, no storage call.
 *
 * The refusals are independent short starts, so they run side by side. No other application process of the
 * contract JVM may be alive while they run (the run contract ends its own in a class-level cleanup), which is what
 * makes "no connection of the application role" a valid check.
 */
@Tag("packaged-jar")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class PackagedJarRefusalContractTest {
    private lateinit var directory: Path

    @BeforeAll
    fun prepare() {
        directory = Files.createTempDirectory("packaged-jar-refusal")
        ContractFakeS3.reset()
    }

    @AfterAll
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun valid(): Map<String, String> =
        PackagedJar.environment(PackagedJar.freePort(), PackagedJar.freePort(), SECRET)

    /** Starts every case at once (four at a time) and returns, per case, the exit code and the console output. */
    private fun refuse(cases: Map<String, Map<String, String>>): Map<String, Pair<Int, String>> {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = cases.map { (name, environment) ->
                name to pool.submit(
                    Callable {
                        val started = PackagedJar.launch(environment, directory, "refusal")
                        try {
                            val ended = started.process.waitFor(60, TimeUnit.SECONDS)
                            assertThat(ended).`as`("$name must end on its own").isTrue()
                            started.process.exitValue() to started.output()
                        } finally {
                            PackagedJar.kill(started)
                        }
                    },
                )
            }
            return results.associate { (name, future) -> name to future.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun assertRefused(results: Map<String, Pair<Int, String>>, secrets: Collection<String>) {
        for ((name, result) in results) {
            val (exit, output) = result
            assertThat(exit).`as`("Exit code when $name").isNotZero()
            assertThat(output).`as`("Output when $name").contains("Missing or invalid explicit setting")
            assertThat(output).`as`("Output when $name").doesNotContain("Starting PawBridgeApplication", "Tomcat started", "Started PawBridge")
            for (secret in secrets) {
                assertThat(output.contains(secret)).`as`("A configured secret appears in the output when $name").isFalse()
            }
            assertThat(output.length).`as`("Output size when $name").isLessThan(OUTPUT_LIMIT_CHARS)
        }
        val connections = ContractPostgres.admin.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE usename = ?", Long::class.javaObjectType, ContractPostgres.APP_ROLE,
        )
        assertThat(connections).`as`("A refused start opens no database connection").isZero()
        assertThat(ContractFakeS3.requests()).`as`("A refused start never contacts the storage").isEmpty()
    }

    private fun allSecrets(environment: Map<String, String>): List<String> = listOf(
        ContractPostgres.appPassword,
        SECRET,
        ContractFakeS3.SECRET_KEY,
        PackagedJar.MAIL_PASSWORD,
    ) + environment.filterKeys { it in PackagedJar.REQUIRED }.values

    @Test
    fun `필수 설정이 하나 빠졌을 때__JAR를 시작하면__그 설정 이름만 밝히고 종료 코드 비영으로 거부한다`() {
        // given
        val environment = valid()
        val cases = PackagedJar.REQUIRED.associateWith { missing -> environment - missing }

        // when
        val results = refuse(cases)

        // then
        assertThat(results.keys).containsExactlyElementsOf(PackagedJar.REQUIRED)
        for ((missing, result) in results) {
            assertThat(result.second).`as`("The output names $missing").contains(missing)
        }
        assertRefused(results, allSecrets(environment).filter { it.length >= MIN_SECRET_LENGTH })
    }

    @Test
    fun `값이 잘못됐을 때__JAR를 시작하면__값을 출력하지 않고 거부한다`() {
        // given
        val environment = valid()
        val credentialUrl = "jdbc:postgresql://synthetic-user:$LEAKY@127.0.0.1:5432/pawbridge_contract"
        val cases = mapOf(
            "PAWBRIDGE_DB_URL" to (environment + ("PAWBRIDGE_DB_URL" to credentialUrl)),
            "PAWBRIDGE_DB_POOL_MAX_SIZE" to (environment + ("PAWBRIDGE_DB_POOL_MAX_SIZE" to "51")),
            "PAWBRIDGE_STORAGE_ENDPOINT" to (environment + ("PAWBRIDGE_STORAGE_ENDPOINT" to "ftp://user:$LEAKY@127.0.0.1")),
            "PAWBRIDGE_JWT_SECRET" to (environment + ("PAWBRIDGE_JWT_SECRET" to " ")),
        )

        // when
        val results = refuse(cases)

        // then
        for ((setting, result) in results) {
            assertThat(result.second).`as`("The output names $setting").contains(setting)
        }
        assertRefused(results, allSecrets(environment) + LEAKY)
    }

    private companion object {
        /** A value that must never be echoed, wherever it is embedded. */
        const val LEAKY = "leaky-synthetic-secret"
        const val SECRET = "refusal-synthetic-signing-secret-0123456789abcdef"
        const val MIN_SECRET_LENGTH = 12
        const val OUTPUT_LIMIT_CHARS = 20_000
    }
}
