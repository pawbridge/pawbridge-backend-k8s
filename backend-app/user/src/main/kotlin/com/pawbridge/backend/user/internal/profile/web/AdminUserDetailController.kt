package com.pawbridge.backend.user.internal.profile.web

import com.pawbridge.backend.user.internal.profile.application.UserProfile
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class AdminUserDetailController(private val profiles: UserProfileQuery) {

    @GetMapping("/api/v1/admin/users/{userId}")
    fun get(@PathVariable("userId") userId: Long): UserProfileController.Response<UserProfile> =
        UserProfileController.Response(200, "요청이 성공적으로 처리되었습니다.", profiles.require(userId))
}
