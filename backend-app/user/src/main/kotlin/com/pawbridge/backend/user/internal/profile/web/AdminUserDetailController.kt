@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package com.pawbridge.backend.user.internal.profile.web

import com.pawbridge.backend.user.internal.profile.application.UserProfile
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class AdminUserDetailController(private val profiles: UserProfileQuery) {

    // The path variable is java.lang.Long deliberately: kotlin.Long is a primitive (a blank segment becomes a type
    // mismatch instead of the Java MissingPathVariableException). A non-null java.lang.Long keeps the Java
    // behaviour: required and boxed, so a blank segment fails before the handler runs.
    @GetMapping("/api/v1/admin/users/{userId}")
    fun get(@PathVariable("userId") userId: java.lang.Long): UserProfileController.Response<UserProfile> =
        UserProfileController.Response(200, "요청이 성공적으로 처리되었습니다.", profiles.require(userId.toLong()))
}
