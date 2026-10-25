package com.pawbridge.backend.community.internal.homevideo.web

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [HomeVideoController::class])
class HomeVideoExceptionHandler {

    @ExceptionHandler(RuntimeException::class)
    fun failure(failure: RuntimeException): ResponseEntity<HomeVideoController.Response<Void>> {
        log.warn("Video curation request failed ({})", failure.javaClass.simpleName)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(
                HomeVideoController.Response<Void>(
                    503,
                    "영상을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                    null,
                ),
            )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(HomeVideoExceptionHandler::class.java)
    }
}
