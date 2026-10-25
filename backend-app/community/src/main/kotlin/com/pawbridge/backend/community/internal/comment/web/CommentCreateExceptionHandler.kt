package com.pawbridge.backend.community.internal.comment.web

import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [CommentCreateController::class])
class CommentCreateExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidContent(failure: MethodArgumentNotValidException): ResponseEntity<CommentCreateController.Response<Void>> {
        val message = failure.bindingResult.fieldErrors
            .joinToString(", ") { error -> error.field + ": " + error.defaultMessage }
        return badRequest(message)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(failure: HttpMessageNotReadableException): ResponseEntity<CommentCreateController.Response<Void>> =
        badRequest("요청 본문 형식이 올바르지 않습니다.")

    @ExceptionHandler(PostNotFoundException::class)
    fun postNotFound(failure: PostNotFoundException): ResponseEntity<CommentCreateController.Response<Void>> =
        ResponseEntity.status(404)
            .body(CommentCreateController.Response<Void>(404, "게시글을 찾을 수 없습니다.", null))

    @ExceptionHandler(UnsupportedCommentCharactersException::class)
    fun unsupportedCharacters(
        failure: UnsupportedCommentCharactersException,
    ): ResponseEntity<CommentCreateController.Response<Void>> = badRequest(failure.message)

    @ExceptionHandler(RuntimeException::class)
    fun serverError(failure: RuntimeException): ResponseEntity<CommentCreateController.Response<Void>> =
        ResponseEntity.internalServerError()
            .body(CommentCreateController.Response<Void>(500, "서버 에러!", null))

    private fun badRequest(message: String?): ResponseEntity<CommentCreateController.Response<Void>> =
        ResponseEntity.badRequest().body(CommentCreateController.Response<Void>(400, message, null))
}
