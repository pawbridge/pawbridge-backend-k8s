package com.pawbridge.backend.user.internal.profile.application

import java.time.LocalDateTime

@JvmRecord
data class UserProfile(
    val userId: Long,
    val email: String?,
    val name: String?,
    val nickname: String?,
    val provider: String?,
    val role: String?,
    val createdAt: LocalDateTime?,
)
