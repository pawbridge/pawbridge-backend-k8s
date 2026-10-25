package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.boot.javaIsBlank
import java.net.URI
import java.util.function.Function

/** Explicit candidate inputs; there is no production address or credential fallback. */
class ReadDatabaseSettings private constructor(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String,
    private val maximumPoolSize: Int,
) {
    fun jdbcUrl(): String = jdbcUrl

    fun username(): String = username

    fun password(): String = password

    fun maximumPoolSize(): Int = maximumPoolSize

    companion object {
        /** Single shared pool for reads and writes; PAWBRIDGE_DB_POOL_MAX_SIZE overrides within 1..50. */
        const val DEFAULT_POOL_MAX_SIZE: Int = 10
        const val MAX_POOL_MAX_SIZE: Int = 50

        @JvmStatic
        fun fromEnvironment(values: Function<String, String?>): ReadDatabaseSettings {
            val url = required(values, "PAWBRIDGE_DB_URL")
            validateUrl(url)
            val username = required(values, "PAWBRIDGE_DB_USER")
            val password = required(values, "PAWBRIDGE_DB_PASSWORD")
            val requestedSize = values.apply("PAWBRIDGE_DB_POOL_MAX_SIZE")
            var maximum = DEFAULT_POOL_MAX_SIZE
            if (requestedSize != null) {
                try {
                    maximum = Integer.parseInt(requestedSize)
                } catch (ignored: NumberFormatException) {
                    throw invalid("PAWBRIDGE_DB_POOL_MAX_SIZE")
                }
            }
            if (maximum < 1 || maximum > MAX_POOL_MAX_SIZE) {
                throw invalid("PAWBRIDGE_DB_POOL_MAX_SIZE")
            }
            return ReadDatabaseSettings(url, username, password, maximum)
        }

        private fun required(values: Function<String, String?>, key: String): String {
            val value = values.apply(key)
            if (value == null || javaIsBlank(value)) {
                throw invalid(key)
            }
            return value
        }

        private fun validateUrl(value: String) {
            try {
                if (!value.startsWith("jdbc:postgresql://")) {
                    throw IllegalArgumentException()
                }
                val uri = URI.create(value.substring(5))
                val path = uri.path
                if (uri.host == null || uri.userInfo != null || uri.rawQuery != null ||
                    uri.fragment != null || path == null ||
                    !Regex("/[a-zA-Z0-9_]+").matches(path) || uri.port == 0 ||
                    uri.port > 65535
                ) {
                    throw IllegalArgumentException()
                }
            } catch (ignored: IllegalArgumentException) {
                throw invalid("PAWBRIDGE_DB_URL")
            }
        }

        private fun invalid(key: String): IllegalArgumentException {
            return IllegalArgumentException("Missing or invalid explicit setting: $key")
        }
    }
}
