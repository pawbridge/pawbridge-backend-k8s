package com.pawbridge.backend.support

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatcher
import org.mockito.ArgumentMatchers

/**
 * Mockito's `any(...)` matchers return null, which Kotlin rejects for non-null parameters.
 * These helpers register the matcher and hand back a placeholder that satisfies the compiler.
 */
inline fun <reified T : Any> anyNonNull(): T {
    ArgumentMatchers.any(T::class.java)
    return uninitialized()
}

/** Like Java's bare `any()`: matches every argument including null. */
fun <T> anyValue(): T {
    ArgumentMatchers.any<T>()
    return uninitialized()
}

@Suppress("UNCHECKED_CAST")
fun <T> uninitialized(): T = null as T

/** `eq(...)` returns null for reference types; hand back the value itself so Kotlin accepts it. */
fun <T> eqValue(value: T): T {
    ArgumentMatchers.eq(value)
    return value
}

/** `same(...)` returns null for reference types; hand back the value itself so Kotlin accepts it. */
fun <T> sameValue(value: T): T {
    ArgumentMatchers.same(value)
    return value
}

/** `captor.capture()` returns null; hand back a placeholder so Kotlin accepts it for non-null parameters. */
fun <T> capture(captor: ArgumentCaptor<T>): T {
    captor.capture()
    return uninitialized()
}

/** `argThat(...)` returns null for reference types; register the matcher and hand back a placeholder. */
fun <T> argThatValue(matcher: ArgumentMatcher<T>): T {
    ArgumentMatchers.argThat(matcher)
    return uninitialized()
}
