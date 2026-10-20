package com.pawbridge.backend.community.internal.post.web;

import com.pawbridge.backend.community.internal.post.application.DeletePost;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class AdminPostDeleteController {
    private final DeletePost delete;

    public AdminPostDeleteController(DeletePost delete) { this.delete=delete; }

    @DeleteMapping("/api/v1/admin/posts/{postId}")
    public ApiResponse<Void> delete(@PathVariable("postId") long postId) {
        delete.deleteAsAdmin(postId);
        return new ApiResponse<>(200,"게시글이 삭제되었습니다.",null);
    }
}
