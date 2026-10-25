package com.pawbridge.backend.community.support

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers

/**
 * Mockito's `any(...)` matchers return null, which Kotlin rejects for non-null parameters.
 * These helpers register the matcher and hand back a placeholder that satisfies the compiler.
 */
inline fun <reified T : Any> anyNonNull(): T {
    ArgumentMatchers.any(T::class.java)
    return uninitialized()
}

@Suppress("UNCHECKED_CAST")
fun <T> uninitialized(): T = null as T

/** `eq(...)` returns null for reference types; hand back the value itself so Kotlin accepts it. */
fun <T : Any> eqNonNull(value: T): T {
    ArgumentMatchers.eq(value)
    return value
}

/** `captor.capture()` returns null; hand back a placeholder so Kotlin accepts it for non-null parameters. */
fun <T : Any> capture(captor: ArgumentCaptor<T>): T {
    captor.capture()
    return uninitialized()
}

/** `matches(regex)` returns null; hand back an empty string so Kotlin accepts it for non-null parameters. */
fun matchesNonNull(regex: String): String {
    ArgumentMatchers.matches(regex)
    return ""
}

/**
 * Matches any vararg array, like `any(Object[].class)` in Java. The returned one-element array is spread into the
 * stubbed call so that Mockito sees as many arguments as registered matchers.
 */
fun anyVarargs(): Array<Any?> {
    ArgumentMatchers.any(Array<Any>::class.java)
    return arrayOf(null)
}
