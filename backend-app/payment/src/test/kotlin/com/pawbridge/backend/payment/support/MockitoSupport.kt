package com.pawbridge.backend.payment.support

import org.mockito.ArgumentMatchers

/**
 * Mockito's `any(...)`, `eq(...)` and `same(...)` return null for reference types, which Kotlin rejects for
 * non-null parameters. These helpers register the matcher and hand back a value that satisfies the compiler.
 */
inline fun <reified T : Any> anyNonNull(): T {
    ArgumentMatchers.any(T::class.java)
    return uninitialized()
}

fun <T : Any> eqNonNull(value: T): T {
    ArgumentMatchers.eq(value)
    return value
}

fun <T : Any> sameNonNull(value: T): T {
    ArgumentMatchers.same(value)
    return value
}

@Suppress("UNCHECKED_CAST")
fun <T> uninitialized(): T = null as T
