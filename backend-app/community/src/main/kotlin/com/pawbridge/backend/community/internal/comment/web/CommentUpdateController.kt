package com.pawbridge.backend.community.internal.comment.web

import com.pawbridge.backend.community.internal.comment.application.CommentDetails
import com.pawbridge.backend.community.internal.comment.application.UpdateComment
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class CommentUpdateController(private val updateComment: UpdateComment) {

    @PutMapping("/api/v1/comments/{commentId}")
    fun update(
        @PathVariable("commentId") commentId: Long,
        @Valid @RequestBody request: Request,
        @AuthenticationPrincipal userId: Long?,
    ): Response<CommentDetails> =
        Response(200, "댓글이 수정되었습니다.", updateComment.update(commentId, userId!!, request.content))

    @JvmRecord
    data class Request(
        @field:NotBlank(message = "내용은 필수입니다")
        val content: String?,
    )

    @JvmRecord
    data class Response<T>(val code: Int, val message: String?, val data: T?)
}
