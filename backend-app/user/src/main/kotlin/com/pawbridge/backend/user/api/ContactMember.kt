package com.pawbridge.backend.user.api

/** Contact projection without private profile or provider information. */
@JvmRecord
data class ContactMember(
    val userId: Long?,
    val nickname: String?,
    val active: Boolean,
    val deletionPending: Boolean,
)
