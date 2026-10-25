package com.pawbridge.backend.community.internal.post.web

import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [PostReadController::class])
class PostReadExceptionHandler {

    @ExceptionHandler(PostNotFoundException::class)
    fun postNotFound(failure: PostNotFoundException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiResponse<Void>(404, "게시글을 찾을 수 없습니다.", null))

    @ExceptionHandler(RuntimeException::class)
    fun serverError(failure: RuntimeException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse<Void>(500, "서버 에러!", null))
}
