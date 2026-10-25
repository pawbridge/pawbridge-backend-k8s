package com.pawbridge.backend.user

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers

/**
 * Mockito matchers that are safe to pass to Kotlin parameters declared non-null.
 *
 * Mockito's own matchers return null (or a platform type), which Kotlin rejects before the mock is
 * called. These register the same matcher and then hand back a non-null stand-in value.
 */
inline fun <reified T : Any> anyNonNull(): T = anyNonNull(T::class.java)

fun <T : Any> anyNonNull(type: Class<T>): T {
    ArgumentMatchers.any(type)
    return uninitialized()
}

fun <T : Any> eqNonNull(value: T): T {
    ArgumentMatchers.eq(value)
    return value
}

fun <T : Any> captureNonNull(captor: ArgumentCaptor<T>): T {
    captor.capture()
    return uninitialized()
}

@Suppress("UNCHECKED_CAST")
private fun <T> uninitialized(): T = null as T
