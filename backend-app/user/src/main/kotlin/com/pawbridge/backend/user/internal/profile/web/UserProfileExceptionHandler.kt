package com.pawbridge.backend.user.internal.profile.web

import com.pawbridge.backend.user.internal.directory.web.AdminUserDirectoryController
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.Collections
import java.util.IdentityHashMap

@RestControllerAdvice(
    assignableTypes = [
        UserProfileController::class,
        AdminUserDetailController::class,
        AdminUserDirectoryController::class,
    ],
)
class UserProfileExceptionHandler {

    @ExceptionHandler(UserNotFoundException::class)
    fun userNotFound(failure: UserNotFoundException): ResponseEntity<UserProfileController.Response<Void>> =
        ResponseEntity.badRequest()
            .body(UserProfileController.Response<Void>(400, "존재하지 않는 회원입니다.", null))

    @ExceptionHandler(RuntimeException::class)
    fun serverError(failure: RuntimeException): ResponseEntity<UserProfileController.Response<Void>> {
        LOG.error("Unexpected user profile lookup failure (exception messages omitted): {}", diagnosticTrace(failure))
        return ResponseEntity.internalServerError().body(UserProfileController.Response<Void>(500, "서버 에러!", null))
    }

    // Match the existing comment update advice's bounded metadata-only diagnostic contract.
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
        private val LOG = LoggerFactory.getLogger(UserProfileExceptionHandler::class.java)
        private const val MAX_LOGGED_CAUSES = 4
        private const val MAX_LOGGED_FRAMES = 6
        private const val MAX_DIAGNOSTIC_LENGTH = 4096
    }
}
