package com.pawbridge.backend.community.internal.post.web

import com.pawbridge.backend.community.internal.post.application.DeletePost
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class PostDeleteController(private val deletePost: DeletePost) {

    @DeleteMapping("/api/v1/posts/{postId}")
    fun delete(
        @PathVariable("postId") postId: Long,
        @AuthenticationPrincipal userId: Long?,
    ): ApiResponse<Void> {
        deletePost.delete(postId, userId!!)
        return ApiResponse(200, "게시글이 삭제되었습니다.", null)
    }
}
