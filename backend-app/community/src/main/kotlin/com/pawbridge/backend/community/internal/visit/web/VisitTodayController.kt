package com.pawbridge.backend.community.internal.visit.web

import com.pawbridge.backend.community.internal.visit.application.ReadTodayVisits
import com.pawbridge.backend.community.internal.visit.application.TodayVisits
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class VisitTodayController(private val readTodayVisits: ReadTodayVisits) {

    @GetMapping("/api/v1/visits/today")
    fun read(): ResponseEntity<Response<TodayVisits>> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(Response(200, "요청이 성공적으로 처리되었습니다.", readTodayVisits.read()))

    @JvmRecord
    data class Response<T>(val code: Int, val message: String?, val data: T?)
}
