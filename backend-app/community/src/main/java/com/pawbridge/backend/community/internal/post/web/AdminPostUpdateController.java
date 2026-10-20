package com.pawbridge.backend.community.internal.post.web;

import com.pawbridge.backend.community.internal.post.application.UpdatePost;
import com.pawbridge.backend.community.internal.post.application.PostDetails;
import com.pawbridge.backend.community.internal.post.application.PostUpload;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public final class AdminPostUpdateController {
    private final UpdatePost update;

    public AdminPostUpdateController(UpdatePost update) { this.update=update; }

    @PutMapping(value="/api/v1/admin/posts/{postId}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PostDetails> update(@PathVariable("postId") long postId,
            @RequestParam(value="title",required=false) String title,
            @RequestParam(value="content",required=false) String content,
            @RequestPart(value="files",required=false) MultipartFile[] files) {
        List<PostUpload> uploads=files==null?List.of():Arrays.stream(files)
                .map(file->new PostUpload(file.getOriginalFilename(),file.getContentType(),file.getSize(),file::getInputStream)).toList();
        return new ApiResponse<>(200,"게시글이 수정되었습니다.",update.updateAsAdmin(postId,title,content,uploads));
    }
}
