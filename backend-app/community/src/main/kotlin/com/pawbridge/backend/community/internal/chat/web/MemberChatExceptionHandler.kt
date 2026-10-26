package com.pawbridge.backend.community.internal.chat.web

import com.pawbridge.backend.community.internal.chat.application.MemberChatRejectedException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice(assignableTypes = [MemberChatController::class])
class MemberChatExceptionHandler {

    @ExceptionHandler(MemberChatRejectedException::class)
    fun rejected(failure: MemberChatRejectedException): ResponseEntity<MemberChatController.Response<Void>> {
        val message = if (failure.status() >= 400 && failure.status() < 500) {
            failure.message
        } else {
            "요청을 처리하지 못했습니다."
        }
        return ResponseEntity.status(failure.status())
            .body(MemberChatController.Response<Void>(failure.status(), message, null))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(): ResponseEntity<MemberChatController.Response<Void>> =
        ResponseEntity.badRequest()
            .body(MemberChatController.Response<Void>(400, "요청 본문 형식이 올바르지 않습니다.", null))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidPathVariable(failure: MethodArgumentTypeMismatchException): ResponseEntity<MemberChatController.Response<Void>> =
        ResponseEntity.badRequest()
            .body(MemberChatController.Response<Void>(400, "요청 경로의 식별자 형식이 올바르지 않습니다.", null))

    @ExceptionHandler(RuntimeException::class)
    fun failed(failure: RuntimeException): ResponseEntity<MemberChatController.Response<Void>> {
        LOG.error("Member chat request failed; exceptionType={}", failure.javaClass.name)
        return ResponseEntity.internalServerError()
            .body(MemberChatController.Response<Void>(500, "서버 에러!", null))
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(MemberChatExceptionHandler::class.java)
    }
}
