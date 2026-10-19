package com.pawbridge.backend.community.internal.post.web;

import com.pawbridge.backend.community.internal.post.application.ListPosts;
import com.pawbridge.backend.community.internal.post.application.PostDetails;
import com.pawbridge.backend.community.internal.post.application.ReadPost;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PostReadController {
    private final ReadPost readPost;
    private final ListPosts listPosts;

    public PostReadController(ReadPost readPost, ListPosts listPosts) {
        this.readPost = readPost;
        this.listPosts = listPosts;
    }

    @GetMapping("/api/v1/posts/read")
    public ApiResponse<List<PostDetails>> read() {
        return new ApiResponse<>(200, "요청이 성공적으로 처리되었습니다.", listPosts.read());
    }

    @GetMapping("/api/v1/posts/read/{postId}")
    public ApiResponse<PostDetails> read(@PathVariable("postId") long postId) {
        return new ApiResponse<>(200, "요청이 성공적으로 처리되었습니다.", readPost.read(postId));
    }
}
