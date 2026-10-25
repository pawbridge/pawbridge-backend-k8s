package com.pawbridge.backend.community.internal.post.web

import com.pawbridge.backend.community.internal.post.application.DeletePost
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class AdminPostDeleteController(private val delete: DeletePost) {

    @DeleteMapping("/api/v1/admin/posts/{postId}")
    fun delete(@PathVariable("postId") postId: Long): ApiResponse<Void> {
        delete.deleteAsAdmin(postId)
        return ApiResponse(200, "게시글이 삭제되었습니다.", null)
    }
}
