package com.pawbridge.backend.user.internal.session.infrastructure

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.regex.Pattern

/** Opaque refresh tokens: 32 random bytes as unpadded base64url. Only their SHA-256 digest is stored. */
class RefreshTokens(private val random: SecureRandom) {

    constructor() : this(SecureRandom())

    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return ENCODER.encodeToString(bytes)
    }

    companion object {
        private const val TOKEN_BYTES = 32
        private val TOKEN_FORMAT: Pattern = Pattern.compile("[A-Za-z0-9_-]{43}")
        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        /** Rejects anything this class could not have generated before it reaches the database. */
        @JvmStatic
        fun wellFormed(token: String?): Boolean = token != null && TOKEN_FORMAT.matcher(token).matches()

        /** Lowercase hex SHA-256, the only form of a token that is persisted or compared in SQL. */
        @JvmStatic
        fun hash(token: String): String {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val hashed = digest.digest(token.toByteArray(StandardCharsets.US_ASCII))
                return HexFormat.of().formatHex(hashed)
            } catch (missing: NoSuchAlgorithmException) {
                throw IllegalStateException("SHA-256 is unavailable", missing)
            }
        }
    }
}
