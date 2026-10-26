package com.pawbridge.backend.user.internal.profile.application

interface UserProfileQuery {
    fun require(userId: Long): UserProfile
}
