package com.pawbridge.backend.animal.internal.apms.infrastructure

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField

/** APMS timestamps may omit fractions or contain up to nanosecond precision. */
object ApmsUpdatedAt {
    private val FORMAT: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd HH:mm:ss")
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .toFormatter()
        .withResolverStyle(ResolverStyle.STRICT)

    @JvmStatic
    fun parse(value: String?): LocalDateTime? {
        if (value == null || isBlank(value)) {
            return null
        }
        try {
            return LocalDateTime.parse(value, FORMAT)
        } catch (exception: RuntimeException) {
            throw IllegalStateException("APMS update timestamp is invalid")
        }
    }

    @JvmStatic
    fun normalize(value: String?): String? {
        val parsed = parse(value)
        return parsed?.format(FORMAT)
    }

    // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
    private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }
}
