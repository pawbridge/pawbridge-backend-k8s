package com.pawbridge.backend.community.internal.comment.web

import com.pawbridge.backend.community.internal.comment.application.CommentDetails
import com.pawbridge.backend.community.internal.comment.application.ListComments
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class CommentReadController(private val listComments: ListComments) {

    @GetMapping("/api/v1/comments/posts/read/{postId}")
    fun read(@PathVariable("postId") postId: Long): Response<List<CommentDetails>> =
        Response(200, "요청이 성공적으로 처리되었습니다.", listComments.read(postId))

    @JvmRecord
    data class Response<T>(val code: Int, val message: String?, val data: T?)
}
