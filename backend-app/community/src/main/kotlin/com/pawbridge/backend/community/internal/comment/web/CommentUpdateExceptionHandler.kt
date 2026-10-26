package com.pawbridge.backend.community.internal.comment.web

import com.pawbridge.backend.community.internal.comment.application.CommentNotFoundException
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException
import com.pawbridge.backend.community.internal.comment.domain.CommentNotOwnedException
import java.util.Collections
import java.util.IdentityHashMap
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice(assignableTypes = [CommentUpdateController::class])
class CommentUpdateExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidContent(failure: MethodArgumentNotValidException): ResponseEntity<CommentUpdateController.Response<Void>> {
        val message = failure.bindingResult.fieldErrors
            .joinToString(", ") { error -> error.field + ": " + error.defaultMessage }
        return ResponseEntity.badRequest().body(CommentUpdateController.Response<Void>(400, message, null))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(failure: HttpMessageNotReadableException): ResponseEntity<CommentUpdateController.Response<Void>> =
        ResponseEntity.badRequest()
            .body(CommentUpdateController.Response<Void>(400, "요청 본문 형식이 올바르지 않습니다.", null))

    @ExceptionHandler(CommentNotFoundException::class)
    fun commentNotFound(failure: CommentNotFoundException): ResponseEntity<CommentUpdateController.Response<Void>> =
        ResponseEntity.status(404)
            .body(CommentUpdateController.Response<Void>(404, "댓글을 찾을 수 없습니다.", null))

    @ExceptionHandler(CommentNotOwnedException::class)
    fun commentNotOwned(failure: CommentNotOwnedException): ResponseEntity<CommentUpdateController.Response<Void>> =
        ResponseEntity.status(403)
            .body(CommentUpdateController.Response<Void>(403, "댓글에 접근할 권한이 없습니다.", null))

    @ExceptionHandler(UnsupportedCommentCharactersException::class)
    fun unsupportedCharacters(
        failure: UnsupportedCommentCharactersException,
    ): ResponseEntity<CommentUpdateController.Response<Void>> =
        ResponseEntity.badRequest()
            .body(CommentUpdateController.Response<Void>(400, failure.message, null))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidPathVariable(failure: MethodArgumentTypeMismatchException): ResponseEntity<CommentUpdateController.Response<Void>> =
        ResponseEntity.badRequest()
            .body(CommentUpdateController.Response<Void>(400, "요청 경로의 식별자 형식이 올바르지 않습니다.", null))

    @ExceptionHandler(RuntimeException::class)
    fun serverError(failure: RuntimeException): ResponseEntity<CommentUpdateController.Response<Void>> {
        LOG.error("Unexpected comment update failure (exception messages omitted): {}", diagnosticTrace(failure))
        return ResponseEntity.internalServerError()
            .body(CommentUpdateController.Response<Void>(500, "서버 에러!", null))
    }

    // Reuse the existing post deletion advice's bounded metadata-only diagnostic contract.
    private fun diagnosticTrace(failure: Throwable): String {
        val trace = StringBuilder()
        val visited: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = failure
        var depth = 0
        while (current != null && depth < MAX_LOGGED_CAUSES && visited.add(current)) {
            trace.append(current.javaClass.name).append('\n')
            val frames = current.stackTrace
            for (index in 0 until minOf(frames.size, MAX_LOGGED_FRAMES)) {
                val frame = frames[index]
                trace.append("  at ").append(frame.className).append('.').append(frame.methodName)
                    .append(":").append(frame.lineNumber).append('\n')
            }
            current = current.cause
            depth++
        }
        return trace.substring(0, minOf(trace.length, MAX_DIAGNOSTIC_LENGTH))
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(CommentUpdateExceptionHandler::class.java)
        private const val MAX_LOGGED_CAUSES = 4
        private const val MAX_LOGGED_FRAMES = 6
        private const val MAX_DIAGNOSTIC_LENGTH = 4096
    }
}
