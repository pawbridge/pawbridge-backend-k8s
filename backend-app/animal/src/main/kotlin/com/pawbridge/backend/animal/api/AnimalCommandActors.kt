package com.pawbridge.backend.animal.api

/** Root resolves the verified principal against the current User account; no header-derived roles. */
fun interface AnimalCommandActors {
    fun find(verifiedUserId: Long): Actor?

    @JvmRecord
    data class Actor(
        val userId: Long,
        val role: String?,
        val careRegNo: String?,
        val active: Boolean,
        val deleted: Boolean,
    )
}
