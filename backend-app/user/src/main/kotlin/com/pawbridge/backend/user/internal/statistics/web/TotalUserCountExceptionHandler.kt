package com.pawbridge.backend.user.internal.statistics.web

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [TotalUserCountController::class, SignupStatisticsController::class])
class TotalUserCountExceptionHandler {

    @ExceptionHandler(RuntimeException::class)
    fun serverError(failure: RuntimeException): ResponseEntity<TotalUserCountController.Response<Void>> {
        LOG.error("User statistics query failed ({})", failure.javaClass.simpleName)
        return ResponseEntity.internalServerError()
            .body(TotalUserCountController.Response<Void>(500, "서버 에러!", null))
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingParameter(
        failure: MissingServletRequestParameterException,
    ): ResponseEntity<TotalUserCountController.Response<Void>> =
        ResponseEntity.badRequest().body(
            TotalUserCountController.Response<Void>(
                400,
                "필수 요청 파라미터 '" + failure.parameterName + "'(이)가 누락되었습니다.",
                null,
            ),
        )

    private companion object {
        private val LOG = LoggerFactory.getLogger(TotalUserCountExceptionHandler::class.java)
    }
}
