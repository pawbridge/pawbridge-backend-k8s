package com.pawbridge.backend.community.internal.comment.web

import com.pawbridge.backend.community.internal.comment.application.CommentDetails
import com.pawbridge.backend.community.internal.comment.application.CreateComment
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class CommentCreateController(private val createComment: CreateComment) {

    @PostMapping("/api/v1/comments/posts/{postId}")
    fun create(
        @PathVariable("postId") postId: Long,
        @Valid @RequestBody request: Request,
        @AuthenticationPrincipal userId: Long?,
    ): Response<CommentDetails> =
        Response(200, "댓글이 생성되었습니다.", createComment.create(postId, userId!!, request.content))

    @JvmRecord
    data class Request(
        @field:NotBlank(message = "내용은 필수입니다")
        val content: String?,
    )

    @JvmRecord
    data class Response<T>(val code: Int, val message: String?, val data: T?)
}
