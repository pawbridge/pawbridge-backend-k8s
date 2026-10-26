package com.pawbridge.backend.contract

import com.pawbridge.backend.boot.configuration.PostWriteSettings
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings
import java.security.SecureRandom
import java.util.HexFormat
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext

/**
 * Does what `PawBridgeApplication.main` does before Spring starts: it builds the two explicit setting objects
 * from environment-style values and registers them as the singletons `readDatabaseSettings` and
 * `postWriteSettings`. The database values point at the shared contract PostgreSQL and the application role.
 *
 * The signing key is random per JVM, so no key is committed. Tests read it back from the registered
 * [PostWriteSettings]. The storage values point at [ContractFakeS3], the in-JVM object storage.
 */
class ContractApplicationInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        val values = environment()
        context.beanFactory.registerSingleton(
            "readDatabaseSettings",
            ReadDatabaseSettings.fromEnvironment { name -> values[name] },
        )
        context.beanFactory.registerSingleton(
            "postWriteSettings",
            PostWriteSettings.fromEnvironment { name -> values[name] },
        )
    }

    private fun environment(): Map<String, String> {
        return mapOf(
            "PAWBRIDGE_DB_URL" to ContractPostgres.jdbcUrl,
            "PAWBRIDGE_DB_USER" to ContractPostgres.APP_ROLE,
            "PAWBRIDGE_DB_PASSWORD" to ContractPostgres.appPassword,
            // Four connections: two concurrent refreshes plus the pool's own idle connection fit comfortably.
            "PAWBRIDGE_DB_POOL_MAX_SIZE" to "4",
            "PAWBRIDGE_JWT_SECRET" to jwtSecret,
            "PAWBRIDGE_STORAGE_ENDPOINT" to ContractFakeS3.endpoint,
            "PAWBRIDGE_STORAGE_BUCKET" to ContractFakeS3.BUCKET,
            "PAWBRIDGE_STORAGE_PUBLIC_BASE_URL" to ContractFakeS3.PUBLIC_BASE_URL,
            "PAWBRIDGE_STORAGE_OBJECT_PREFIX" to ContractFakeS3.OBJECT_PREFIX,
            "PAWBRIDGE_STORAGE_ACCESS_KEY_ID" to ContractFakeS3.ACCESS_KEY,
            "PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY" to ContractFakeS3.SECRET_KEY,
            "PAWBRIDGE_STORAGE_REGION" to "us-east-1",
        )
    }

    companion object {
        /** The signing secret of this JVM. Tests only compare against it, for example to prove a log does not hold it. */
        val jwtSecret: String = randomHex(32)

        private fun randomHex(byteCount: Int): String {
            val bytes = ByteArray(byteCount)
            SecureRandom().nextBytes(bytes)
            return HexFormat.of().formatHex(bytes)
        }
    }
}
