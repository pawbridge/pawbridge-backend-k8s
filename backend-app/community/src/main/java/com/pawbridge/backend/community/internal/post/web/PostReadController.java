package com.pawbridge.backend.community.internal.post.web;

import com.pawbridge.backend.community.internal.post.application.PostDetails;
import com.pawbridge.backend.community.internal.post.application.ReadPost;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PostReadController {
    private final ReadPost readPost;

    public PostReadController(ReadPost readPost) {
        this.readPost = readPost;
    }

    @GetMapping("/api/v1/posts/read/{postId}")
    public ApiResponse<PostDetails> read(@PathVariable("postId") long postId) {
        return new ApiResponse<>(200, "요청이 성공적으로 처리되었습니다.", readPost.read(postId));
    }
}
