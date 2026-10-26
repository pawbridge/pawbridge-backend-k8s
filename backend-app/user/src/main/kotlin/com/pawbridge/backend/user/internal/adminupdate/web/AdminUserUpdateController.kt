@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package com.pawbridge.backend.user.internal.adminupdate.web

import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class AdminUserUpdateController(private val members: AdminUserUpdate) {

    // The path variable is java.lang.Long deliberately: kotlin.Long? makes it optional (a blank segment reaches the
    // service as null) and kotlin.Long is a primitive (a blank segment becomes a type mismatch). A non-null
    // java.lang.Long keeps the Java behaviour: required and boxed, so a blank segment fails before the handler runs.
    // The body stays non-null: a nullable Kotlin @RequestBody would make the body optional for Spring MVC.
    @PutMapping("/api/v1/admin/users/{userId}")
    fun update(
        @PathVariable("userId") userId: java.lang.Long,
        @Valid @RequestBody request: AdminUserUpdateRequest,
    ): UserNicknameController.Response<Void> {
        members.update(userId.toLong(), AdminUserUpdate.Changes(request.nickname, request.role, request.careRegNo))
        return UserNicknameController.Response(200, "회원 정보가 수정되었습니다.", null)
    }
}
