package com.pawbridge.backend.user.internal.favorite.web

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.Collections
import java.util.IdentityHashMap

@RestControllerAdvice(assignableTypes = [FavoriteStatusController::class])
class FavoriteStatusExceptionHandler {

    @ExceptionHandler(RuntimeException::class)
    fun serverError(failure: RuntimeException): ResponseEntity<FavoriteStatusController.Response<Void>> {
        // The original advice's Hibernate-only handler does not catch Spring path conversion failures.
        LOG.error("Unexpected favorite status lookup failure (exception messages omitted): {}", diagnosticTrace(failure))
        return ResponseEntity.internalServerError()
            .body(FavoriteStatusController.Response<Void>(500, "서버 에러!", null))
    }

    private fun diagnosticTrace(failure: Throwable): String {
        val trace = StringBuilder()
        val visited: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())
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
        private val LOG = LoggerFactory.getLogger(FavoriteStatusExceptionHandler::class.java)
        private const val MAX_LOGGED_CAUSES = 4
        private const val MAX_LOGGED_FRAMES = 6
        private const val MAX_DIAGNOSTIC_LENGTH = 4096
    }
}
