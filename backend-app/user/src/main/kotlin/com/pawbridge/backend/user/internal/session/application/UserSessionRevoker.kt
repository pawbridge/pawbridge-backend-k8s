package com.pawbridge.backend.user.internal.session.application

/** Revokes every session of a user inside the caller's current transaction. */
fun interface UserSessionRevoker {
    fun revokeAll(userId: Long)

    companion object {
        @JvmField
        val NONE: UserSessionRevoker = UserSessionRevoker { }
    }
}
