package com.pawbridge.backend.community.internal.homevideo.web

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [HomeVideoManagementController::class])
class HomeVideoManagementExceptionHandler {

    @ExceptionHandler(ResponseStatusException::class)
    fun status(failure: ResponseStatusException): ResponseEntity<HomeVideoController.Response<Void>> =
        ResponseEntity.status(failure.statusCode)
            .body(HomeVideoController.Response<Void>(failure.statusCode.value(), failure.reason, null))

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun invalid(): ResponseEntity<HomeVideoController.Response<Void>> =
        ResponseEntity.badRequest()
            .body(HomeVideoController.Response<Void>(400, "영상 주소와 요청 형식을 확인해 주세요.", null))

    @ExceptionHandler(RuntimeException::class)
    fun failure(failure: RuntimeException): ResponseEntity<HomeVideoController.Response<Void>> {
        LoggerFactory.getLogger(HomeVideoManagementExceptionHandler::class.java)
            .warn("Video curation request failed ({})", failure.javaClass.simpleName)
        return ResponseEntity.status(503)
            .body(
                HomeVideoController.Response<Void>(
                    503,
                    "영상을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                    null,
                ),
            )
    }
}
