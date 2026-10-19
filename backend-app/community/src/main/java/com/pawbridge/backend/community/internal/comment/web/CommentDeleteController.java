package com.pawbridge.backend.community.internal.comment.web;

import com.pawbridge.backend.community.internal.comment.application.CommentDeletion;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class CommentDeleteController {
    private final CommentDeletion deletion;

    public CommentDeleteController(CommentDeletion deletion) {
        this.deletion = deletion;
    }

    @DeleteMapping("/api/v1/comments/{commentId}")
    public Response<Void> delete(@PathVariable("commentId") long commentId, @AuthenticationPrincipal Long userId) {
        deletion.delete(commentId, userId);
        return new Response<>(200, "댓글이 삭제되었습니다.", null);
    }

    public record Response<T>(int code, String message, T data) {
    }
}
