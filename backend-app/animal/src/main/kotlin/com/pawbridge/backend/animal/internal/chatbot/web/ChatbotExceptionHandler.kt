package com.pawbridge.backend.animal.internal.chatbot.web

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception.ChatbotAnimalNotFoundException
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception.ChatbotRateLimitExceededException
import com.pawbridge.backend.animal.internal.command.infrastructure.persistence.PostgresqlRollbackCharsetViolation
import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BindException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Original chatbot envelopes, scoped to this controller; no question/history/key logging. */
@RestControllerAdvice(assignableTypes = [ChatbotController::class])
class ChatbotExceptionHandler {

    @ExceptionHandler(ChatbotAnimalNotFoundException::class)
    fun animalMissing(failure: ChatbotAnimalNotFoundException): ResponseEntity<Failure> {
        return error(404, failure.message)
    }

    @ExceptionHandler(ChatbotRateLimitExceededException::class)
    fun quota(failure: ChatbotRateLimitExceededException): ResponseEntity<Failure> {
        return error(429, failure.message)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(failure: IllegalArgumentException): ResponseEntity<Failure> {
        return error(400, failure.message)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(failure: MethodArgumentNotValidException): ResponseEntity<Failure> {
        val message = failure.bindingResult.fieldErrors
            .joinToString(", ") { field -> field.field + ": " + field.defaultMessage }
        return error(400, message)
    }

    @ExceptionHandler(BindException::class)
    fun binding(failure: BindException): ResponseEntity<Failure> {
        return error(400, failure.bindingResult.allErrors.first().defaultMessage)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(failure: HttpMessageNotReadableException): ResponseEntity<Failure> {
        return error(400, "요청 본문 형식이 올바르지 않습니다.")
    }

    @ExceptionHandler(DataAccessException::class)
    fun database(failure: DataAccessException): ResponseEntity<Failure> {
        if (PostgresqlRollbackCharsetViolation.matches(failure)) {
            return error(400, PostgresqlRollbackCharsetViolation.MESSAGE)
        }
        return error(500, "데이터베이스 오류가 발생했습니다.")
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(failure: Exception): ResponseEntity<Failure> {
        if (PostgresqlRollbackCharsetViolation.matches(failure)) {
            return error(400, PostgresqlRollbackCharsetViolation.MESSAGE)
        }
        return error(500, "서버 내부 오류가 발생했습니다.")
    }

    @JvmRecord
    data class Failure(
        val code: Int,
        val message: String?,
        val data: Any?,
    )

    private companion object {
        private fun error(code: Int, message: String?): ResponseEntity<Failure> {
            return ResponseEntity.status(code).body(Failure(code, message, null))
        }
    }
}
