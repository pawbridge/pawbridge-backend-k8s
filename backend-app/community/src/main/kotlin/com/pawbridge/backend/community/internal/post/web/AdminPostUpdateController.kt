package com.pawbridge.backend.community.internal.post.web

import com.pawbridge.backend.community.internal.post.application.PostDetails
import com.pawbridge.backend.community.internal.post.application.PostUpload
import com.pawbridge.backend.community.internal.post.application.UpdatePost
import java.util.Collections
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
class AdminPostUpdateController(private val update: UpdatePost) {

    @PutMapping(value = ["/api/v1/admin/posts/{postId}"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun update(
        @PathVariable("postId") postId: Long,
        @RequestParam(value = "title", required = false) title: String?,
        @RequestParam(value = "content", required = false) content: String?,
        @RequestPart(value = "files", required = false) files: Array<MultipartFile>?,
    ): ApiResponse<PostDetails> {
        val uploads: List<PostUpload> = toUploads(files)
        return ApiResponse(200, "게시글이 수정되었습니다.", update.updateAsAdmin(postId, title, content, uploads))
    }

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
