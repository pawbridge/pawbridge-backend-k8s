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

    // The path variable stays boxed like the Java signature.
    // The body stays non-null: a nullable Kotlin @RequestBody would make the body optional for Spring MVC.
    @PutMapping("/api/v1/admin/users/{userId}")
    fun update(
        @PathVariable("userId") userId: Long?,
        @Valid @RequestBody request: AdminUserUpdateRequest,
    ): UserNicknameController.Response<Void> {
        members.update(userId!!, AdminUserUpdate.Changes(request.nickname, request.role, request.careRegNo))
        return UserNicknameController.Response(200, "회원 정보가 수정되었습니다.", null)
    }
}
