package com.pawbridge.backend.user.internal.deletion.web

import com.pawbridge.backend.user.internal.deletion.application.UserDeletion
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/** Only the existing admin DELETE; Boot must enforce verified ROLE_ADMIN before this controller. */
@RestController
class AdminUserDeletionController(private val deletion: UserDeletion) {

    // The path variable stays boxed like the Java signature (reflection tests look the method up by Long).
    @DeleteMapping("/api/v1/admin/users/{userId}")
    fun delete(@PathVariable("userId") userId: Long?): ResponseEntity<Response<Void>> {
        deletion.delete(userId!!)
        return ResponseEntity.ok(Response(200, "회원이 삭제되었습니다.", null))
    }

    @JvmRecord
    data class Response<T>(
        val code: Int,
        val message: String?,
        val data: T?,
    )
}
