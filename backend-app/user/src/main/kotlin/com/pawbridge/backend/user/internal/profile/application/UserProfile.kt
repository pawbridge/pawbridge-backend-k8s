package com.pawbridge.backend.user.internal.profile.application

import java.time.LocalDateTime

data class UserProfile(
    val userId: Long,
    val email: String?,
    val name: String?,
    val nickname: String?,
    val provider: String?,
    val role: String?,
    val createdAt: LocalDateTime?,
) {
    // The Java record accessors stay for Java callers; the standard getters keep the JSON shape.
    fun userId(): Long = userId

    fun email(): String? = email

    fun name(): String? = name

    fun nickname(): String? = nickname

    fun provider(): String? = provider

    fun role(): String? = role

    fun createdAt(): LocalDateTime? = createdAt
}
