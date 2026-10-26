package com.pawbridge.backend.store.api

/** Root supplies current User state without a Store-to-User implementation dependency. */
interface StoreOrderActors {
    @JvmRecord
    data class Actor(
        val userId: Long,
        val active: Boolean,
        val deleted: Boolean,
    )

    /** Runs inside the active writer transaction; the implementation locks the User row FOR UPDATE. */
    fun currentForWrite(verifiedUserId: Long): Actor?

    /** Runs inside the order read-only transaction; no User write lock is requested. */
    fun currentForRead(verifiedUserId: Long): Actor?
}
