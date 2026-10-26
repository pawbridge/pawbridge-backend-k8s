package com.pawbridge.backend.community.internal.post.web

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MultipartException

/** Multipart parsing can fail before a controller exists, so controller-scoped advice cannot handle it. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class PostMultipartExceptionHandler {

    @ExceptionHandler(MultipartException::class)
    fun parsingFailure(failure: MultipartException): ResponseEntity<ApiResponse<Void>> {
        LOG.warn("Post multipart parsing failed (exception message omitted): {}", failure.javaClass.name)
        return ResponseEntity.internalServerError().body(ApiResponse<Void>(500, "서버 에러!", null))
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(PostMultipartExceptionHandler::class.java)
    }
}
