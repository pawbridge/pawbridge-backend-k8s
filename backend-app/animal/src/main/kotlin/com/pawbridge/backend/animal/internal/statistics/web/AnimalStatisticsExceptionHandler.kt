package com.pawbridge.backend.animal.internal.statistics.web

import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [AnimalStatisticsController::class, AdminAnimalStatisticsController::class])
class AnimalStatisticsExceptionHandler {

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(failure: IllegalArgumentException): ResponseEntity<Failure> = error(400, failure.message)

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingParameter(failure: MissingServletRequestParameterException): ResponseEntity<Failure> {
        return error(400, "필수 요청 파라미터 '" + failure.parameterName + "'(이)가 누락되었습니다.")
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
