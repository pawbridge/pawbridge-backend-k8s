package com.pawbridge.backend.animal.internal.discovery.web

import com.pawbridge.backend.animal.internal.discovery.application.AnimalNotFoundException
import com.pawbridge.backend.animal.internal.discovery.application.SearchProjectionPendingException
import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.validation.BindException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [AnimalDiscoveryController::class])
class AnimalDiscoveryExceptionHandler {

    @ExceptionHandler(AnimalNotFoundException::class)
    fun notFound(failure: AnimalNotFoundException): ResponseEntity<Failure> = error(404, failure.message)

    @ExceptionHandler(SearchProjectionPendingException::class)
    fun pending(failure: SearchProjectionPendingException): ResponseEntity<Failure> = error(503, failure.message)

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(failure: IllegalArgumentException): ResponseEntity<Failure> = error(400, failure.message)

    @ExceptionHandler(BindException::class)
    fun binding(failure: BindException): ResponseEntity<Failure> {
        return error(400, failure.bindingResult.allErrors.first().defaultMessage)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(failure: MethodArgumentNotValidException): ResponseEntity<Failure> {
        val message = failure.bindingResult.fieldErrors
            .joinToString(", ") { field -> field.field + ": " + field.defaultMessage }
        return error(400, message)
    }

    @ExceptionHandler(DataAccessException::class)
    fun database(failure: DataAccessException): ResponseEntity<Failure> = error(500, "데이터베이스 오류가 발생했습니다.")

    @ExceptionHandler(Exception::class)
    fun unexpected(failure: Exception): ResponseEntity<Failure> = error(500, "서버 내부 오류가 발생했습니다.")

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
