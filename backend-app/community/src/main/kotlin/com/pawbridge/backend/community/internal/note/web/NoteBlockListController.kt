package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.BlockPage
import com.pawbridge.backend.community.internal.note.application.ListNoteBlocks
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class NoteBlockListController(
    private val blocks: ListNoteBlocks,
) {

    @GetMapping("/api/v1/notes/blocks")
    fun blocks(
        @AuthenticationPrincipal currentUserId: Long?,
        @RequestParam(name = "page", defaultValue = "0") page: Int,
    ): ResponseEntity<Response<BlockPage>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Response(200, "요청이 성공적으로 처리되었습니다.", blocks.read(currentUserId!!, page)))

    @JvmRecord
    data class Response<T>(
        val code: Int,
        val message: String?,
        val data: T?,
    )
}
