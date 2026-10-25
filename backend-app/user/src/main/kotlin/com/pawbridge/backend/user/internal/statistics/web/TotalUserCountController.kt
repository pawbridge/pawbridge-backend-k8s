package com.pawbridge.backend.user.internal.statistics.web

import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class TotalUserCountController(private val users: TotalUserCountQuery) {

    @GetMapping("/api/v1/admin/users/stats/total-users")
    fun count(): Response<Long> = Response(200, "요청이 성공적으로 처리되었습니다.", users.count())

    // The Java record accessors stay for Java callers; the standard getters keep the JSON shape.
    data class Response<T>(
        val code: Int,
        val message: String?,
        val data: T?,
    ) {
        fun code(): Int = code

        fun message(): String? = message

        fun data(): T? = data
    }
}
