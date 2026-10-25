package com.pawbridge.backend.animal.internal.image.web

import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageForbiddenException
import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageTooLargeException
import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Original GlobalExceptionHandler cases reachable from the image routes, scoped to this controller. */
@RestControllerAdvice(assignableTypes = [AnimalImageController::class])
class AnimalImageExceptionHandler {
    @ExceptionHandler(AnimalImageForbiddenException::class)
    fun forbidden(failure: AnimalImageForbiddenException): ResponseEntity<Failure> = error(403, failure.message)

    @ExceptionHandler(AnimalImageTooLargeException::class)
    fun tooLarge(failure: AnimalImageTooLargeException): ResponseEntity<Failure> = error(413, failure.message)

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(failure: IllegalArgumentException): ResponseEntity<Failure> = error(400, failure.message)

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missing(failure: MissingServletRequestParameterException): ResponseEntity<Failure> {
        return error(400, String.format("필수 요청 파라미터 '%s'(이)가 누락되었습니다.", failure.parameterName))
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
