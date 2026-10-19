package com.pawbridge.backend.community.internal.comment.web;

import com.pawbridge.backend.community.internal.comment.application.CommentDetails;
import com.pawbridge.backend.community.internal.comment.application.ListComments;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class CommentReadController {
    private final ListComments listComments;

    public CommentReadController(ListComments listComments) {
        this.listComments = listComments;
    }

    @GetMapping("/api/v1/comments/posts/read/{postId}")
    public Response<List<CommentDetails>> read(@PathVariable("postId") long postId) {
        return new Response<>(200, "요청이 성공적으로 처리되었습니다.", listComments.read(postId));
    }

    public record Response<T>(int code, String message, T data) {
    }
}
