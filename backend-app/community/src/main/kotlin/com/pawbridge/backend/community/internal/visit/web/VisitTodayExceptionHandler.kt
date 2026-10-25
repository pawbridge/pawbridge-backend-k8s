package com.pawbridge.backend.community.internal.visit.web

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [VisitTodayController::class])
class VisitTodayExceptionHandler {

    @ExceptionHandler(DataAccessException::class)
    fun storageUnavailable(): ResponseEntity<VisitTodayController.Response<Void>> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .cacheControl(CacheControl.noStore())
            .body(VisitTodayController.Response<Void>(503, "방문 통계를 불러올 수 없습니다.", null))

    @ExceptionHandler(RuntimeException::class)
    fun serverError(failure: RuntimeException): ResponseEntity<VisitTodayController.Response<Void>> {
        log.warn("Visit today request failed ({})", failure.javaClass.simpleName)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(VisitTodayController.Response<Void>(500, "서버 에러!", null))
    }

    private companion object {
        private val log = LoggerFactory.getLogger(VisitTodayExceptionHandler::class.java)
    }
}
