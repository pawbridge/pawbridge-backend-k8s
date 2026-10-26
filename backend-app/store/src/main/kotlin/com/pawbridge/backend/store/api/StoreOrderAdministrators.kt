package com.pawbridge.backend.store.api

/** Current User authority inside the caller's shared Store writer transaction. */
interface StoreOrderAdministrators {
    @JvmRecord
    data class Actor(
        val userId: Long,
        val active: Boolean,
        val deleted: Boolean,
        val role: String?,
    )

    fun currentForRead(userId: Long): Actor?

    fun currentForWrite(userId: Long): Actor?
}
