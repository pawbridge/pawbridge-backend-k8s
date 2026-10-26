package com.pawbridge.backend.community.internal.report.web

import com.pawbridge.backend.community.internal.post.web.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException

@RestControllerAdvice(assignableTypes = [AnimalReportController::class])
class AnimalReportExceptionHandler {

    @ExceptionHandler(ResponseStatusException::class)
    fun rejected(failure: ResponseStatusException): ResponseEntity<ApiResponse<Void>> {
        val code = failure.statusCode.value()
        val reason = failure.reason
        val message = if (failure.statusCode.is4xxClientError && reason != null) {
            reason
        } else {
            "요청을 처리하지 못했습니다."
        }
        return ResponseEntity.status(code).body(ApiResponse<Void>(code, message, null))
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidParameter(): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.badRequest()
            .body(ApiResponse<Void>(400, "제보 검색의 종류·날짜·페이지 형식을 확인해 주세요.", null))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun invalidBody(): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.badRequest().body(ApiResponse<Void>(400, "요청 본문 형식이 올바르지 않습니다.", null))

    @ExceptionHandler(RuntimeException::class)
    fun failure(failure: RuntimeException): ResponseEntity<ApiResponse<Void>> {
        LOG.error("Report request failed (exception message omitted): {}", failure.javaClass.name)
        return ResponseEntity.internalServerError().body(ApiResponse<Void>(500, "서버 에러!", null))
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(AnimalReportExceptionHandler::class.java)
    }
}
