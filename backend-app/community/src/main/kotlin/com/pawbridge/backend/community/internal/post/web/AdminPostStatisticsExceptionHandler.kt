package com.pawbridge.backend.community.internal.post.web

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException

@RestControllerAdvice(assignableTypes = [AdminPostStatisticsController::class])
class AdminPostStatisticsExceptionHandler {

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun malformedDate(): ResponseEntity<Void> = ResponseEntity.badRequest().build()

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingDate(failure: MissingServletRequestParameterException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.badRequest().body(
            ApiResponse<Void>(
                400,
                "필수 요청 파라미터 '" + failure.parameterName + "'(이)가 누락되었습니다.",
                null,
            ),
        )

    @ExceptionHandler(ResponseStatusException::class)
    fun rejectedRange(failure: ResponseStatusException): ResponseEntity<ApiResponse<Void>> {
        val status = failure.statusCode.value()
        val message = if (failure.statusCode.is4xxClientError) failure.reason else null
        return ResponseEntity.status(status).body(
            ApiResponse<Void>(status, message ?: "요청을 처리하지 못했습니다.", null),
        )
    }

    @ExceptionHandler(RuntimeException::class)
    fun serverError(failure: RuntimeException): ResponseEntity<ApiResponse<Void>> {
        log.error("Administrator post statistics failed ({})", failure.javaClass.simpleName)
        return ResponseEntity.internalServerError().body(ApiResponse<Void>(500, "서버 에러!", null))
    }

    private companion object {
        private val log = LoggerFactory.getLogger(AdminPostStatisticsExceptionHandler::class.java)
    }
}
