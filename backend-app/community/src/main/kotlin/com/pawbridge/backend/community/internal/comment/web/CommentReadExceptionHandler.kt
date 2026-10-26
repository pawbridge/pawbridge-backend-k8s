package com.pawbridge.backend.community.internal.comment.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice(assignableTypes = [CommentReadController::class])
class CommentReadExceptionHandler {

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidPathVariable(failure: MethodArgumentTypeMismatchException): ResponseEntity<CommentReadController.Response<Void>> =
        ResponseEntity.badRequest()
            .body(CommentReadController.Response<Void>(400, "요청 경로의 식별자 형식이 올바르지 않습니다.", null))

    @ExceptionHandler(RuntimeException::class)
    fun serverError(failure: RuntimeException): ResponseEntity<CommentReadController.Response<Void>> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(CommentReadController.Response<Void>(500, "서버 에러!", null))
}
