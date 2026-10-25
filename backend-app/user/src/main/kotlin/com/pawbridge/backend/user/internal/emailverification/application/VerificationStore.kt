package com.pawbridge.backend.user.internal.emailverification.application

/** Redis-shaped verification state; the adapter keeps the original atomic counters. */
interface VerificationStore {
    /** Original checkAndIncrement script: false when the existing count already reached the limit. */
    fun tryIncrementSendCount(key: String, maxAttempts: Int, ttlSeconds: Long): Boolean

    fun get(key: String): String?

    fun put(key: String, value: String, ttlSeconds: Long)

    fun delete(key: String)

    /** Original incrementWithExpire script. */
    fun incrementWithExpire(key: String, ttlSeconds: Long)
}
