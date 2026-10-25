package com.pawbridge.backend.community.internal.comment.web

import com.pawbridge.backend.community.internal.comment.application.CommentDeletion
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class CommentDeleteController(private val deletion: CommentDeletion) {

    @DeleteMapping("/api/v1/comments/{commentId}")
    fun delete(
        @PathVariable("commentId") commentId: Long,
        @AuthenticationPrincipal userId: Long?,
    ): Response<Void> {
        deletion.delete(commentId, userId!!)
        return Response(200, "댓글이 삭제되었습니다.", null)
    }

    @JvmRecord
    data class Response<T>(val code: Int, val message: String?, val data: T?)
}
