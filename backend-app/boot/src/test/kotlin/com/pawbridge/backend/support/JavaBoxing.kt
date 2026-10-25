package com.pawbridge.backend.support

/**
 * Some controllers declare `java.lang.Long` parameters, which Kotlin does not accept for a `Long` literal.
 * Tests that call those methods directly use this helper to build the boxed value.
 */
@Suppress("DEPRECATION", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
fun javaLong(value: Long): java.lang.Long = java.lang.Long(value)
