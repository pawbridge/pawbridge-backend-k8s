package com.pawbridge.backend.community.internal.post.web;

import com.pawbridge.backend.community.internal.post.application.DeletePost;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PostDeleteController {
    private final DeletePost deletePost;

    public PostDeleteController(DeletePost deletePost) {
        this.deletePost = deletePost;
    }

    @DeleteMapping("/api/v1/posts/{postId}")
    public ApiResponse<Void> delete(@PathVariable("postId") long postId, @AuthenticationPrincipal Long userId) {
        deletePost.delete(postId, userId);
        return new ApiResponse<>(200, "게시글이 삭제되었습니다.", null);
    }
}
