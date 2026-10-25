package com.pawbridge.backend.community.internal.comment.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [CommentReadController::class])
class CommentReadExceptionHandler {

    @ExceptionHandler(RuntimeException::class)
    fun serverError(failure: RuntimeException): ResponseEntity<CommentReadController.Response<Void>> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(CommentReadController.Response<Void>(500, "서버 에러!", null))
}
