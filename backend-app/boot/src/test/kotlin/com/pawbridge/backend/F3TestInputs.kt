package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.PostWriteSettings
import com.pawbridge.backend.boot.security.PostTokenAuthenticator
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.Date
import javax.crypto.SecretKey

object F3TestInputs {
    const val JWT_SECRET = "f3-synthetic-key-only-never-for-production-012345678901234567890123456789"
    @JvmField
    val KEY: SecretKey = Keys.hmacShaKeyFor(JWT_SECRET.toByteArray(Charsets.UTF_8))

    @JvmStatic
    fun values(): Map<String, String> {
        val values = HashMap<String, String>()
        values["PAWBRIDGE_DB_URL"] = "jdbc:postgresql://127.0.0.1:5432/pawbridge_f2_ci"
        values["PAWBRIDGE_DB_USER"] = "fixture-reader"
        values["PAWBRIDGE_DB_PASSWORD"] = "synthetic-reader-only"
        values["PAWBRIDGE_JWT_SECRET"] = JWT_SECRET
        values["PAWBRIDGE_STORAGE_ENDPOINT"] = "http://127.0.0.1:39001"
        values["PAWBRIDGE_STORAGE_BUCKET"] = "f3-test-bucket"
        values["PAWBRIDGE_STORAGE_PUBLIC_BASE_URL"] = "https://fixture.invalid"
        values["PAWBRIDGE_STORAGE_OBJECT_PREFIX"] = "f3-test/"
        values["PAWBRIDGE_STORAGE_ACCESS_KEY_ID"] = "synthetic-access"
        values["PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY"] = "synthetic-secret-only"
        values["PAWBRIDGE_STORAGE_REGION"] = "us-east-1"
        return values
    }

    @JvmStatic
    fun settings(): PostWriteSettings {
        val inputs = values()
        return PostWriteSettings.fromEnvironment { name -> inputs[name] }
    }

    @JvmStatic
    fun token(userId: Long, role: String): String {
        return Jwts.builder()
            .subject("synthetic@example.invalid")
            .claim("userId", userId)
            .claim("role", role)
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(KEY)
            .compact()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class SecurityInputs {
        @Bean
        fun postTokenAuthenticator(): PostTokenAuthenticator = PostTokenAuthenticator(KEY)
    }
}
