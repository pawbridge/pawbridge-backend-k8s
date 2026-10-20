package com.pawbridge.backend.community.internal.post.web;

import com.pawbridge.backend.community.internal.post.application.PostDetails;
import com.pawbridge.backend.community.internal.post.application.PostPageReader;
import com.pawbridge.backend.community.internal.post.application.ReadAdminPosts;
import com.pawbridge.backend.community.internal.post.application.ReadPost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class AdminPostReadController {
    private final ReadAdminPosts posts;
    private final ReadPost post;
    public AdminPostReadController(ReadAdminPosts posts, ReadPost post) { this.posts = posts; this.post = post; }
    @GetMapping("/api/v1/admin/posts")
    public ApiResponse<Page<PostDetails>> list(
            @PageableDefault(size=20, sort="createdAt", direction=Sort.Direction.DESC) Pageable pageable) {
        var request = new PostPageReader.Request(pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort().stream()
                .map(order -> new PostPageReader.Order(order.getProperty(), order.isAscending(), order.isIgnoreCase())).toList());
        var result = posts.read(request);
        return new ApiResponse<>(200, "요청이 성공적으로 처리되었습니다.", new PageImpl<>(result.content(), pageable, result.total()));
    }
    @GetMapping("/api/v1/admin/posts/{postId}")
    public ApiResponse<PostDetails> detail(@PathVariable("postId") long postId) {
        return new ApiResponse<>(200, "요청이 성공적으로 처리되었습니다.", post.read(postId));
    }
}
