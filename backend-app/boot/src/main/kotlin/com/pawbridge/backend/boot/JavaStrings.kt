package com.pawbridge.backend.boot

// Kotlin's String helpers differ from Java's for NBSP, control characters and trailing empty tokens.
// Settings, CORS and chat origin parsing keep the Java semantics through the helpers below.

/**
 * Java String.isBlank(): empty or only Character.isWhitespace characters.
 * Kotlin's isBlank also accepts NBSP and other Unicode spaces, so settings validation uses this instead.
 */
internal fun javaIsBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

/** Java String.trim(): removes leading and trailing characters up to U+0020 (Kotlin's trim removes Unicode spaces). */
internal fun javaTrim(value: String): String = value.trim { it <= ' ' }

/** Java String.strip(): removes leading and trailing Character.isWhitespace characters. */
internal fun javaStrip(value: String): String = value.trim { Character.isWhitespace(it) }

/**
 * Java String.split(",") for a single-character delimiter: trailing empty strings are dropped,
 * except that an input without any delimiter is returned as the only element.
 */
internal fun javaSplit(value: String, delimiter: Char): List<String> {
    val parts = value.split(delimiter)
    if (parts.size == 1) {
        return parts
    }
    return parts.dropLastWhile { it.isEmpty() }
}
