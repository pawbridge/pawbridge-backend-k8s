@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package com.pawbridge.backend.user.internal.deletion.web

import com.pawbridge.backend.user.internal.deletion.application.UserDeletion
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/** Only the existing admin DELETE; Boot must enforce verified ROLE_ADMIN before this controller. */
@RestController
class AdminUserDeletionController(private val deletion: UserDeletion) {

    // The path variable is java.lang.Long deliberately: kotlin.Long? makes it optional (a blank segment reaches the
    // service as null) and kotlin.Long is a primitive (a blank segment becomes a type mismatch, and reflection tests
    // look the method up by Long.class). A non-null java.lang.Long keeps the Java behaviour: required and boxed,
    // so a blank segment fails before the handler runs.
    @DeleteMapping("/api/v1/admin/users/{userId}")
    fun delete(@PathVariable("userId") userId: java.lang.Long): ResponseEntity<Response<Void>> {
        deletion.delete(userId.toLong())
        return ResponseEntity.ok(Response(200, "회원이 삭제되었습니다.", null))
    }

    @JvmRecord
    data class Response<T>(
        val code: Int,
        val message: String?,
        val data: T?,
    )
}
