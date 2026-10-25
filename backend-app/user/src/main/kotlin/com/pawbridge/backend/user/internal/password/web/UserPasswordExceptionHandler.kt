package com.pawbridge.backend.user.internal.password.web

import com.pawbridge.backend.user.internal.password.application.InvalidCurrentPasswordException
import com.pawbridge.backend.user.internal.password.application.OAuthUserCannotChangePasswordException
import com.pawbridge.backend.user.internal.password.application.SamePasswordException
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.Collections
import java.util.IdentityHashMap

@RestControllerAdvice(assignableTypes = [UserPasswordController::class])
class UserPasswordExceptionHandler {

    @ExceptionHandler(UserNotFoundException::class)
    fun userNotFound(failure: UserNotFoundException): ResponseEntity<UserPasswordController.Response<Void>> =
        badRequest("존재하지 않는 회원입니다.")

    @ExceptionHandler(OAuthUserCannotChangePasswordException::class)
    fun socialAccount(
        failure: OAuthUserCannotChangePasswordException,
    ): ResponseEntity<UserPasswordController.Response<Void>> =
        badRequest("소셜 로그인 사용자는 비밀번호를 변경할 수 없습니다.")

    @ExceptionHandler(InvalidCurrentPasswordException::class)
    fun invalidCurrent(failure: InvalidCurrentPasswordException): ResponseEntity<UserPasswordController.Response<Void>> =
        badRequest("현재 비밀번호가 일치하지 않습니다.")

    @ExceptionHandler(SamePasswordException::class)
    fun samePassword(failure: SamePasswordException): ResponseEntity<UserPasswordController.Response<Void>> =
        badRequest("현재 비밀번호와 동일한 비밀번호로 변경할 수 없습니다.")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(failure: MethodArgumentNotValidException): ResponseEntity<UserPasswordController.Response<Void>> {
        // Only field names and fixed constraint messages are returned; rejected values are secrets.
        val message = failure.bindingResult.fieldErrors
            .joinToString(", ") { error -> error.field + ": " + error.defaultMessage }
        return badRequest(message)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(failure: HttpMessageNotReadableException): ResponseEntity<UserPasswordController.Response<Void>> =
        badRequest("요청 본문 형식이 올바르지 않습니다.")

    @ExceptionHandler(RuntimeException::class)
    fun serverError(failure: RuntimeException): ResponseEntity<UserPasswordController.Response<Void>> {
        LOG.error("Unexpected user password update failure (exception messages omitted): {}", diagnosticTrace(failure))
        return ResponseEntity.internalServerError()
            .body(UserPasswordController.Response<Void>(500, "서버 에러!", null))
    }

    private fun badRequest(message: String): ResponseEntity<UserPasswordController.Response<Void>> =
        ResponseEntity.badRequest().body(UserPasswordController.Response<Void>(400, message, null))

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
        private val LOG = LoggerFactory.getLogger(UserPasswordExceptionHandler::class.java)
        private const val MAX_LOGGED_CAUSES = 4
        private const val MAX_LOGGED_FRAMES = 6
        private const val MAX_DIAGNOSTIC_LENGTH = 4096
    }
}
