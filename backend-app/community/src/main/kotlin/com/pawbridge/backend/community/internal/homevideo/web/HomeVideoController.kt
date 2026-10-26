package com.pawbridge.backend.community.internal.homevideo.web

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo
import com.pawbridge.backend.community.internal.homevideo.application.ReadHomeVideos
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HomeVideoController(private val readHomeVideos: ReadHomeVideos) {

    @GetMapping("/api/v1/home/videos")
    fun read(): Response<List<HomeVideo>> =
        Response(200, "요청이 성공적으로 처리되었습니다.", readHomeVideos.read())

    @JvmRecord
    data class Response<T>(val code: Int, val message: String?, val data: T?)
}
