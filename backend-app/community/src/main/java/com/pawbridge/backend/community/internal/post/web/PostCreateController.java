package com.pawbridge.backend.community.internal.post.web;

import com.pawbridge.backend.community.internal.post.application.CreatePost;
import com.pawbridge.backend.community.internal.post.application.PostDetails;
import com.pawbridge.backend.community.internal.post.application.PostUpload;
import com.pawbridge.backend.community.internal.post.domain.PostBoardType;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public final class PostCreateController {
    private final CreatePost createPost;

    public PostCreateController(CreatePost createPost) {
        this.createPost = createPost;
    }

    @PostMapping(value = "/api/v1/posts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PostDetails> create(
            @RequestParam("title") String title,
            @RequestParam("content") String content,
            @RequestParam("boardType") PostBoardType boardType,
            @RequestPart(value = "files", required = false) MultipartFile[] files,
            @AuthenticationPrincipal Long userId) {
        List<PostUpload> uploads = files == null ? List.of() : Arrays.stream(files)
                .map(file -> new PostUpload(file.getOriginalFilename(), file.getContentType(),
                        file.getSize(), file::getInputStream))
                .toList();
        return new ApiResponse<>(200, "게시글이 생성되었습니다.",
                createPost.create(userId, title, content, boardType, uploads));
    }
}
