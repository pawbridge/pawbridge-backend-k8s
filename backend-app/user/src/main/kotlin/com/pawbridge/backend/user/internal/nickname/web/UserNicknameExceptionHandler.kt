package com.pawbridge.backend.user.internal.nickname.web

import com.pawbridge.backend.user.internal.nickname.application.NicknameDuplicateException
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import org.postgresql.util.PSQLException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.sql.SQLException
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

@RestControllerAdvice(assignableTypes = [UserNicknameController::class])
class UserNicknameExceptionHandler {

    @ExceptionHandler(UserNotFoundException::class)
    fun userNotFound(failure: UserNotFoundException): ResponseEntity<UserNicknameController.Response<Void>> =
        badRequest("존재하지 않는 회원입니다.")

    @ExceptionHandler(NicknameDuplicateException::class)
    fun duplicate(failure: NicknameDuplicateException): ResponseEntity<UserNicknameController.Response<Void>> =
        ResponseEntity.status(409)
            .body(UserNicknameController.Response<Void>(409, "이미 사용 중인 닉네임입니다.", null))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(failure: MethodArgumentNotValidException): ResponseEntity<UserNicknameController.Response<Void>> {
        val message = failure.bindingResult.fieldErrors
            .joinToString(", ") { error -> error.field + ": " + error.defaultMessage }
        return badRequest(message)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(failure: HttpMessageNotReadableException): ResponseEntity<UserNicknameController.Response<Void>> =
        badRequest("요청 본문 형식이 올바르지 않습니다.")

    @ExceptionHandler(RuntimeException::class)
    fun serverError(failure: RuntimeException): ResponseEntity<UserNicknameController.Response<Void>> {
        // The application proxy commits after the store returns, so classify at the HTTP boundary.
        if (isRollbackCharsetViolation(failure)) {
            return badRequest("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.")
        }
        LOG.error("Unexpected user nickname update failure (exception messages omitted): {}", diagnosticTrace(failure))
        return ResponseEntity.internalServerError()
            .body(UserNicknameController.Response<Void>(500, "서버 에러!", null))
    }

    private fun badRequest(message: String): ResponseEntity<UserNicknameController.Response<Void>> =
        ResponseEntity.badRequest().body(UserNicknameController.Response<Void>(400, message, null))

    private fun isRollbackCharsetViolation(failure: Throwable): Boolean {
        val visited: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())
        val pending = ArrayDeque<Throwable>()
        pending.add(failure)
        while (!pending.isEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) {
                continue
            }
            if (current is PSQLException) {
                val details = current.serverErrorMessage
                val constraint = details?.constraint
                if ("23514" == current.sqlState &&
                    details != null &&
                    "pawbridge_user" == details.schema &&
                    constraint != null &&
                    constraint.startsWith("ck_rollback_charset_")
                ) {
                    return true
                }
            }
            if (current.cause != null) {
                pending.addLast(current.cause)
            }
            if (current is SQLException && current.nextException != null) {
                pending.addLast(current.nextException)
            }
        }
        return false
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
        private val LOG = LoggerFactory.getLogger(UserNicknameExceptionHandler::class.java)
        private const val MAX_LOGGED_CAUSES = 4
        private const val MAX_LOGGED_FRAMES = 6
        private const val MAX_DIAGNOSTIC_LENGTH = 4096
    }
}
