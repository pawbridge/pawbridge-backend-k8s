package com.pawbridge.backend.community.internal.post.web

import com.pawbridge.backend.community.internal.post.application.CreatePost
import com.pawbridge.backend.community.internal.post.application.PostDetails
import com.pawbridge.backend.community.internal.post.application.PostUpload
import com.pawbridge.backend.community.internal.post.domain.PostBoardType
import java.util.Collections
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
class PostCreateController(private val createPost: CreatePost) {

    @PostMapping(value = ["/api/v1/posts"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun create(
        @RequestParam("title") title: String,
        @RequestParam("content") content: String,
        @RequestParam("boardType") boardType: PostBoardType,
        @RequestPart(value = "files", required = false) files: Array<MultipartFile>?,
        @AuthenticationPrincipal userId: Long?,
    ): ApiResponse<PostDetails> {
        val uploads: List<PostUpload> = toUploads(files)
        return ApiResponse(
            200,
            "게시글이 생성되었습니다.",
            createPost.create(userId!!, title, content, boardType, uploads),
        )
    }

    // The stream is opened lazily by the uploader, never while the controller maps the multipart files.
    private fun toUploads(files: Array<MultipartFile>?): List<PostUpload> {
        if (files == null) {
            return java.util.List.of()
        }
        val uploads = files.map { file ->
            PostUpload(file.originalFilename, file.contentType, file.size, PostUpload.Content { file.inputStream })
        }
        return Collections.unmodifiableList(uploads)
    }
}
