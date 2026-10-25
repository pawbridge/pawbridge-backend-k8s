package com.pawbridge.backend.community.internal.visit.web

import com.pawbridge.backend.community.internal.visit.application.VisitCollectionRejectedException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [VisitCollectionController::class])
class VisitCollectionExceptionHandler {

    @ExceptionHandler(VisitCollectionRejectedException::class)
    fun rejected(failure: VisitCollectionRejectedException): ResponseEntity<*> =
        error(
            if (failure.reason() == VisitCollectionRejectedException.Reason.COOKIE) 409 else 400,
            failure.message,
        )

    @ExceptionHandler(ResponseStatusException::class)
    fun status(failure: ResponseStatusException): ResponseEntity<*> =
        error(failure.statusCode.value(), failure.reason)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun date(failure: MethodArgumentTypeMismatchException): ResponseEntity<*> =
        error(400, "날짜 형식을 확인해 주세요.")

    @ExceptionHandler(DataAccessException::class)
    fun unavailable(failure: DataAccessException): ResponseEntity<*> =
        error(503, "방문 통계를 불러올 수 없습니다.")

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missing(failure: MissingServletRequestParameterException): ResponseEntity<*> =
        ResponseEntity.badRequest().body(
            VisitCollectionController.Response<Void>(
                400,
                "필수 요청 파라미터 '%s'(이)가 누락되었습니다.".format(failure.parameterName),
                null,
            ),
        )

    @ExceptionHandler(RuntimeException::class)
    fun runtime(failure: RuntimeException): ResponseEntity<*> {
        log.warn("Visit collection request failed ({})", failure.javaClass.simpleName)
        return ResponseEntity.internalServerError()
            .body(VisitCollectionController.Response<Void>(500, "서버 에러!", null))
    }

    private fun error(code: Int, message: String?): ResponseEntity<*> =
        ResponseEntity.status(code)
            .cacheControl(CacheControl.noStore())
            .body(VisitCollectionController.Response<Void>(code, message, null))

    private companion object {
        private val log = LoggerFactory.getLogger(VisitCollectionExceptionHandler::class.java)
    }
}
