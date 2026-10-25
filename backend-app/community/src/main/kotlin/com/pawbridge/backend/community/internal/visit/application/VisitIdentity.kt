package com.pawbridge.backend.community.internal.visit.application

import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Day-scoped, host-only identity. No account, IP or device fingerprint. */
class VisitIdentity(base64Key: String) {

    private val key: ByteArray
    private val random = SecureRandom()

    init {
        try {
            key = Base64.getDecoder().decode(base64Key)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Visit signing key must be base64.")
        }
        if (key.size < 32) {
            throw IllegalArgumentException("Visit signing key must contain at least 32 bytes.")
        }
    }

    fun day(now: Instant): LocalDate = now.atZone(ZONE).toLocalDate()

    fun identity(value: String?, day: LocalDate): String? {
        if (value == null || value.length > 180) {
            return null
        }
        // Java split with a negative limit keeps trailing empty strings, like Kotlin's literal split.
        val parts = value.split(".")
        if (parts.size != 4 ||
            parts[0] != "v1" ||
            parts[1] != day.toString() ||
            !TOKEN.matches(parts[2]) ||
            !TOKEN.matches(parts[3])
        ) {
            return null
        }
        try {
            if (DECODER.decode(parts[2]).size != 32 || DECODER.decode(parts[3]).size != 32) {
                return null
            }
            val payload = parts[0] + "." + parts[1] + "." + parts[2]
            return if (MessageDigest.isEqual(mac("cookie:$payload"), DECODER.decode(parts[3]))) parts[2] else null
        } catch (failure: IllegalArgumentException) {
            return null
        }
    }

    fun initialize(value: String?, now: Instant): IssuedIdentity? {
        val today = day(now)
        if (identity(value, today) != null) {
            return null
        }
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val payload = "v1." + today + "." + ENCODER.encodeToString(bytes)
        val signed = payload + "." + ENCODER.encodeToString(mac("cookie:$payload"))
        val midnight = today.plusDays(1).atStartOfDay(ZONE).toInstant()
        // Floor instead of extending the identity past the next midnight.
        val seconds = maxOf(1L, Duration.between(now, midnight).seconds)
        return IssuedIdentity(signed, seconds)
    }

    @JvmRecord
    data class IssuedIdentity(val value: String, val maxAgeSeconds: Long)

    fun hash(day: LocalDate, identity: String): String =
        HexFormat.of().formatHex(mac("dedup:$day:$identity"))

    private fun mac(value: String): ByteArray {
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        } catch (failure: GeneralSecurityException) {
            throw IllegalStateException("Visit signing unavailable.")
        }
    }

    companion object {
        const val NAME: String = "__Host-pb_visit"

        @JvmField
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        private val TOKEN = Regex("[A-Za-z0-9_-]{43}")
        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}
