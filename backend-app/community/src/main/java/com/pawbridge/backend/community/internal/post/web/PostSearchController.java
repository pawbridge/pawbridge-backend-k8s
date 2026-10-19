package com.pawbridge.backend.community.internal.post.web;

import com.pawbridge.backend.community.internal.post.application.PostDetails;
import com.pawbridge.backend.community.internal.post.application.SearchPosts;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PostSearchController {
    private final SearchPosts searchPosts;

    public PostSearchController(SearchPosts searchPosts) {
        this.searchPosts = searchPosts;
    }

    @GetMapping("/api/v1/posts/search")
    public ApiResponse<List<PostDetails>> search(@RequestParam("keyword") String keyword) {
        return new ApiResponse<>(200, "요청이 성공적으로 처리되었습니다.", searchPosts.search(keyword));
    }
}
