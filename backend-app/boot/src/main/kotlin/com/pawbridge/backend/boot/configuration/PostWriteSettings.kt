package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.boot.javaIsBlank
import io.jsonwebtoken.security.Keys
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.function.Function
import javax.crypto.SecretKey

/** Explicit candidate-only inputs. Values and keys are never rendered in diagnostics. */
class PostWriteSettings private constructor(values: Function<String, String?>) {
    private val bucket: String
    private val publicBaseUrl: String
    private val objectPrefix: String
    private val accessKey: String
    private val secretKey: String
    private val region: String
    private val endpoint: URI
    private val jwtKey: SecretKey

    init {
        jwtKey = try {
            Keys.hmacShaKeyFor(required(values, "PAWBRIDGE_JWT_SECRET").toByteArray(StandardCharsets.UTF_8))
        } catch (failure: RuntimeException) {
            throw invalid("PAWBRIDGE_JWT_SECRET")
        }
        endpoint = address(required(values, "PAWBRIDGE_STORAGE_ENDPOINT"), "PAWBRIDGE_STORAGE_ENDPOINT", true)
        bucket = required(values, "PAWBRIDGE_STORAGE_BUCKET")
        if (!Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]").matches(bucket)) {
            throw invalid("PAWBRIDGE_STORAGE_BUCKET")
        }
        publicBaseUrl = required(values, "PAWBRIDGE_STORAGE_PUBLIC_BASE_URL")
        address(publicBaseUrl, "PAWBRIDGE_STORAGE_PUBLIC_BASE_URL", false)
        objectPrefix = optionalPrefix(values, "PAWBRIDGE_STORAGE_OBJECT_PREFIX")
        if (objectPrefix.isNotEmpty() && !Regex("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*/?").matches(objectPrefix)) {
            throw invalid("PAWBRIDGE_STORAGE_OBJECT_PREFIX")
        }
        accessKey = required(values, "PAWBRIDGE_STORAGE_ACCESS_KEY_ID")
        secretKey = required(values, "PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY")
        region = required(values, "PAWBRIDGE_STORAGE_REGION")
    }

    fun jwtKey(): SecretKey = jwtKey

    fun endpoint(): URI = endpoint

    fun bucket(): String = bucket

    fun publicBaseUrl(): String = publicBaseUrl

    fun objectPrefix(): String = objectPrefix

    fun accessKey(): String = accessKey

    fun secretKey(): String = secretKey

    fun region(): String = region

    companion object {
        @JvmStatic
        fun fromEnvironment(values: Function<String, String?>): PostWriteSettings {
            return PostWriteSettings(values)
        }

        private fun required(values: Function<String, String?>, key: String): String {
            val value = values.apply(key)
            if (value == null || javaIsBlank(value)) {
                throw invalid(key)
            }
            return value
        }

        /** Unset and empty both mean the bucket root. Any other value, including blanks, must match the prefix pattern. */
        private fun optionalPrefix(values: Function<String, String?>, key: String): String {
            val value = values.apply(key)
            return value ?: ""
        }

        private fun address(value: String, key: String, endpoint: Boolean): URI {
            try {
                val uri = URI.create(value)
                if (!("http" == uri.scheme || "https" == uri.scheme) ||
                    uri.host == null || uri.rawUserInfo != null || uri.rawQuery != null ||
                    uri.rawFragment != null || uri.port == 0 || uri.port > 65535 ||
                    (endpoint && !(uri.path.isEmpty() || "/" == uri.path))
                ) {
                    throw invalid(key)
                }
                return uri
            } catch (failure: IllegalArgumentException) {
                throw invalid(key)
            }
        }

        private fun invalid(key: String): IllegalArgumentException {
            return IllegalArgumentException("Missing or invalid explicit setting: $key")
        }
    }
}
