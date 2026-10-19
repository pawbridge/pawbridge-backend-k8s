package com.pawbridge.backend.community.internal.comment.web;

import com.pawbridge.backend.community.internal.comment.application.CommentDetails;
import com.pawbridge.backend.community.internal.comment.application.UpdateComment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class CommentUpdateController {
    private final UpdateComment updateComment;

    public CommentUpdateController(UpdateComment updateComment) {
        this.updateComment = updateComment;
    }

    @PutMapping("/api/v1/comments/{commentId}")
    public Response<CommentDetails> update(@PathVariable("commentId") long commentId,
            @Valid @RequestBody Request request, @AuthenticationPrincipal Long userId) {
        return new Response<>(200, "댓글이 수정되었습니다.",
                updateComment.update(commentId, userId, request.content()));
    }

    public record Request(@NotBlank(message = "내용은 필수입니다") String content) {
    }

    public record Response<T>(int code, String message, T data) {
    }
}
