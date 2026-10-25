package com.pawbridge.backend.community.internal.post.web

import com.pawbridge.backend.community.internal.post.application.PostDetails
import com.pawbridge.backend.community.internal.post.application.PostPageReader
import com.pawbridge.backend.community.internal.post.application.ReadAdminPosts
import com.pawbridge.backend.community.internal.post.application.ReadPost
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class AdminPostReadController(
    private val posts: ReadAdminPosts,
    private val post: ReadPost,
) {

    @GetMapping("/api/v1/admin/posts")
    fun list(
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): ApiResponse<Page<PostDetails>> {
        val orders = pageable.sort.stream()
            .map { order -> PostPageReader.Order(order.property, order.isAscending, order.isIgnoreCase) }
            .toList()
        val request = PostPageReader.Request(pageable.pageNumber, pageable.pageSize, orders)
        val result = posts.read(request)
        return ApiResponse(
            200,
            "요청이 성공적으로 처리되었습니다.",
            PageImpl(result.content(), pageable, result.total()),
        )
    }

    @GetMapping("/api/v1/admin/posts/{postId}")
    fun detail(@PathVariable("postId") postId: Long): ApiResponse<PostDetails> =
        ApiResponse(200, "요청이 성공적으로 처리되었습니다.", post.read(postId))
}
