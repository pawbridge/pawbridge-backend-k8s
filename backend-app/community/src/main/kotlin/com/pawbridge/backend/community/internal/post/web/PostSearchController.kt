package com.pawbridge.backend.community.internal.post.web

import com.pawbridge.backend.community.internal.post.application.PostDetails
import com.pawbridge.backend.community.internal.post.application.SearchPosts
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class PostSearchController(private val searchPosts: SearchPosts) {

    @GetMapping("/api/v1/posts/search")
    fun search(@RequestParam("keyword") keyword: String): ApiResponse<List<PostDetails>> =
        ApiResponse(200, "요청이 성공적으로 처리되었습니다.", searchPosts.search(keyword))
}
