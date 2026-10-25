package com.pawbridge.backend.user.internal.signup.application

@JvmRecord
data class SignedUp(
    val userId: Long,
    val email: String?,
    val name: String?,
    val nickname: String?,
)
