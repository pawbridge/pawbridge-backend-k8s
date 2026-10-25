package com.pawbridge.backend.community.internal.homevideo.web

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Board
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Metadata
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Order
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Publication
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Save
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class HomeVideoManagementController(private val videos: HomeVideoManagement) {

    @JvmRecord
    data class PreviewRequest(
        @field:NotBlank
        @field:Size(max = 2048)
        val url: String?,
    )

    @JvmRecord
    data class SaveRequest(
        @field:NotBlank
        @field:Size(max = 2048)
        val url: String?,
        val published: Boolean,
        @field:Min(0)
        val revision: Long,
    )

    @JvmRecord
    data class PublicationRequest(
        val published: Boolean,
        @field:Min(0)
        val revision: Long,
    )

    @GetMapping("/api/v1/admin/videos")
    fun board(): HomeVideoController.Response<Board> = ok(videos.board())

    @PostMapping("/api/v1/admin/videos/preview")
    fun preview(@Valid @RequestBody request: PreviewRequest): HomeVideoController.Response<Metadata> =
        ok(videos.preview(request.url))

    @PostMapping("/api/v1/admin/videos")
    fun create(@Valid @RequestBody request: SaveRequest): HomeVideoController.Response<Board> =
        ok(videos.save(null, Save(request.url, request.published, request.revision)))

    @PutMapping("/api/v1/admin/videos/{id}")
    fun edit(
        @PathVariable("id") id: UUID,
        @Valid @RequestBody request: SaveRequest,
    ): HomeVideoController.Response<Board> =
        ok(videos.save(id, Save(request.url, request.published, request.revision)))

    @PutMapping("/api/v1/admin/videos/{id}/publication")
    fun publish(
        @PathVariable("id") id: UUID,
        @Valid @RequestBody request: PublicationRequest,
    ): HomeVideoController.Response<Board> =
        ok(videos.publish(id, Publication(request.published, request.revision)))

    @PutMapping("/api/v1/admin/videos/order")
    fun order(@Valid @RequestBody request: HomeVideoOrderRequest): HomeVideoController.Response<Board> =
        ok(videos.reorder(Order(request.ids, request.revision)))

    @PostMapping("/api/v1/admin/videos/{id}/recheck")
    fun recheck(
        @PathVariable("id") id: UUID,
        @Valid @RequestBody request: PublicationRequest,
    ): HomeVideoController.Response<Board> =
        ok(videos.recheck(id, request.revision))

    private fun <T> ok(value: T): HomeVideoController.Response<T> =
        HomeVideoController.Response(200, "요청이 성공적으로 처리되었습니다.", value)
}
