package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.NoteStreamRejectedException
import com.pawbridge.backend.community.internal.note.application.NoteStreamRejectedException.Reason
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.async.AsyncRequestTimeoutException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [NoteStreamController::class])
class NoteStreamExceptionHandler {

    @ExceptionHandler(NoteStreamRejectedException::class)
    fun rejected(
        failure: NoteStreamRejectedException,
        response: HttpServletResponse,
    ): ResponseEntity<Map<String, String>>? =
        when (failure.reason()) {
            Reason.LOGIN -> error(401, "로그인이 필요합니다.", response)
            Reason.INACTIVE -> error(403, "이용할 수 없는 회원입니다.", response)
        }

    @ExceptionHandler(ResponseStatusException::class)
    fun status(
        failure: ResponseStatusException,
        response: HttpServletResponse,
    ): ResponseEntity<Map<String, String>>? {
        if (response.isCommitted) {
            return null
        }
        response.setHeader("Cache-Control", "no-store")
        return ResponseEntity.status(failure.statusCode).headers(failure.headers)
            .cacheControl(CacheControl.noStore())
            .body(mapOf("message" to (failure.reason ?: "요청을 처리할 수 없습니다.")))
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidRequest(response: HttpServletResponse): ResponseEntity<Map<String, String>>? =
        error(400, "요청 형식과 로그인 정보를 확인해 주세요.", response)

    @ExceptionHandler(ContactMemberUnavailableException::class)
    fun memberUnavailable(response: HttpServletResponse): ResponseEntity<Map<String, String>>? =
        error(503, "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.", response)

    @ExceptionHandler(DataAccessException::class)
    fun storageUnavailable(response: HttpServletResponse): ResponseEntity<Map<String, String>>? =
        error(503, "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.", response)

    // An open stream ends with this when its emitter times out and the client reconnects, so it is not a failure
    // and is not logged. The response stays the same as before for a stream that has not started writing.
    @ExceptionHandler(AsyncRequestTimeoutException::class)
    fun streamTimedOut(response: HttpServletResponse): ResponseEntity<Map<String, String>>? =
        error(503, "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", response)

    @ExceptionHandler(RuntimeException::class)
    fun unexpectedFailure(
        failure: RuntimeException,
        response: HttpServletResponse,
    ): ResponseEntity<Map<String, String>>? {
        log.warn("Private note request failed ({})", failure.javaClass.simpleName)
        return error(503, "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", response)
    }

    private fun error(
        code: Int,
        message: String,
        response: HttpServletResponse,
    ): ResponseEntity<Map<String, String>>? {
        if (response.isCommitted) {
            return null
        }
        response.setHeader("Cache-Control", "no-store")
        return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(mapOf("message" to message))
    }

    private companion object {
        private val log = LoggerFactory.getLogger(NoteStreamExceptionHandler::class.java)
    }
}
