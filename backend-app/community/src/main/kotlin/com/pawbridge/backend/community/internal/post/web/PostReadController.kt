package com.pawbridge.backend.community.internal.post.web

import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.PostDetails
import com.pawbridge.backend.community.internal.post.application.ReadPost
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class PostReadController(
    private val readPost: ReadPost,
    private val listPosts: ListPosts,
) {

    @GetMapping("/api/v1/posts/read")
    fun read(): ApiResponse<List<PostDetails>> =
        ApiResponse(200, "요청이 성공적으로 처리되었습니다.", listPosts.read())

    @GetMapping("/api/v1/posts/read/{postId}")
    fun read(@PathVariable("postId") postId: Long): ApiResponse<PostDetails> =
        ApiResponse(200, "요청이 성공적으로 처리되었습니다.", readPost.read(postId))
}
