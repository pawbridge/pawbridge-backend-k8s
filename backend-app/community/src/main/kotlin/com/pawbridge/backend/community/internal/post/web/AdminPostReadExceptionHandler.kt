package com.pawbridge.backend.community.internal.post.web

import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice(assignableTypes = [AdminPostReadController::class])
class AdminPostReadExceptionHandler {

    @ExceptionHandler(PostNotFoundException::class)
    fun missing(failure: PostNotFoundException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.status(404).body(ApiResponse<Void>(404, "게시글을 찾을 수 없습니다.", null))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidPathVariable(failure: MethodArgumentTypeMismatchException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.badRequest()
            .body(ApiResponse<Void>(400, "요청 경로의 식별자 형식이 올바르지 않습니다.", null))

    @ExceptionHandler(RuntimeException::class)
    fun failure(failure: RuntimeException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.status(500).body(ApiResponse<Void>(500, "서버 에러!", null))
}
