package com.pawbridge.backend.community.internal.comment.web;

import com.pawbridge.backend.community.internal.comment.application.CommentDetails;
import com.pawbridge.backend.community.internal.comment.application.CreateComment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class CommentCreateController {
    private final CreateComment createComment;

    public CommentCreateController(CreateComment createComment) {
        this.createComment = createComment;
    }

    @PostMapping("/api/v1/comments/posts/{postId}")
    public Response<CommentDetails> create(@PathVariable("postId") long postId,
            @Valid @RequestBody Request request, @AuthenticationPrincipal Long userId) {
        return new Response<>(200, "댓글이 생성되었습니다.",
                createComment.create(postId, userId, request.content()));
    }

    public record Request(@NotBlank(message = "내용은 필수입니다") String content) {
    }

    public record Response<T>(int code, String message, T data) {
    }
}
