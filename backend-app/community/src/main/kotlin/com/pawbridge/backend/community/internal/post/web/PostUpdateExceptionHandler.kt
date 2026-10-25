package com.pawbridge.backend.community.internal.post.web

import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.application.UnsupportedPostCharactersException
import com.pawbridge.backend.community.internal.post.domain.PostNotOwnedException
import java.util.Collections
import java.util.IdentityHashMap
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [PostUpdateController::class, AdminPostUpdateController::class])
class PostUpdateExceptionHandler {

    @ExceptionHandler(PostNotFoundException::class)
    fun postNotFound(failure: PostNotFoundException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiResponse<Void>(404, "게시글을 찾을 수 없습니다.", null))

    @ExceptionHandler(PostNotOwnedException::class)
    fun postNotOwned(failure: PostNotOwnedException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiResponse<Void>(403, "게시글에 접근할 권한이 없습니다.", null))

    @ExceptionHandler(UnsupportedPostCharactersException::class)
    fun unsupportedCharacters(failure: UnsupportedPostCharactersException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.badRequest().body(ApiResponse<Void>(400, failure.message, null))

    @ExceptionHandler(RuntimeException::class)
    fun serverError(failure: RuntimeException): ResponseEntity<ApiResponse<Void>> {
        LOG.error("Unexpected post update failure (exception messages omitted): {}", diagnosticTrace(failure))
        return ResponseEntity.internalServerError().body(ApiResponse<Void>(500, "서버 에러!", null))
    }

    // Match the existing creation advice's bounded metadata-only diagnostic contract.
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
        private val LOG = LoggerFactory.getLogger(PostUpdateExceptionHandler::class.java)
        private const val MAX_LOGGED_CAUSES = 4
        private const val MAX_LOGGED_FRAMES = 6
        private const val MAX_DIAGNOSTIC_LENGTH = 4096
    }
}
