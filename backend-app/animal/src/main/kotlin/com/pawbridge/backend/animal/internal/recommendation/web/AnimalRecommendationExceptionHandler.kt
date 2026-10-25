package com.pawbridge.backend.animal.internal.recommendation.web

import com.pawbridge.backend.animal.internal.discovery.application.AnimalNotFoundException
import com.pawbridge.backend.animal.internal.recommendation.application.RecommendationUnavailableException
import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [AnimalRecommendationController::class])
class AnimalRecommendationExceptionHandler {

    @ExceptionHandler(AnimalNotFoundException::class)
    fun missing(failure: AnimalNotFoundException): ResponseEntity<Failure> = error(404, failure.message)

    @ExceptionHandler(RecommendationUnavailableException::class)
    fun unavailable(failure: RecommendationUnavailableException): ResponseEntity<Failure> = error(503, failure.message)

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(failure: IllegalArgumentException): ResponseEntity<Failure> = error(400, failure.message)

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
