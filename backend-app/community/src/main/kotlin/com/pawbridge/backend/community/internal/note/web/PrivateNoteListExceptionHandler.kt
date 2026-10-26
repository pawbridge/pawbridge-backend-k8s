package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.PrivateNoteListRejectedException
import com.pawbridge.backend.community.internal.note.application.PrivateNoteListRejectedException.Reason
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [PrivateNoteListController::class])
class PrivateNoteListExceptionHandler {

    @ExceptionHandler(PrivateNoteListRejectedException::class)
    fun rejected(failure: PrivateNoteListRejectedException): ResponseEntity<Map<String, String>> =
        when (failure.reason()) {
            Reason.INVALID_PAGE -> error(400, "페이지 범위를 확인해 주세요.")
            Reason.INVALID_BOX -> error(400, "쪽지함을 확인해 주세요.")
            Reason.LOGIN -> error(401, "로그인이 필요합니다.")
            Reason.INACTIVE -> error(403, "이용할 수 없는 회원입니다.")
        }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidRequest(): ResponseEntity<Map<String, String>> =
        error(400, "요청 형식과 로그인 정보를 확인해 주세요.")

    @ExceptionHandler(ContactMemberUnavailableException::class)
    fun memberUnavailable(): ResponseEntity<Map<String, String>> =
        error(503, "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.")

    @ExceptionHandler(
        DataAccessException::class,
        CannotCreateTransactionException::class,
        TransactionSystemException::class,
        TransactionTimedOutException::class,
    )
    fun storageUnavailable(): ResponseEntity<Map<String, String>> =
        error(503, "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.")

    @ExceptionHandler(RuntimeException::class)
    fun unexpectedFailure(failure: RuntimeException): ResponseEntity<Map<String, String>> {
        log.warn("Private note request failed ({})", failure.javaClass.simpleName)
        return error(503, "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.")
    }

    private fun error(
        status: Int,
        message: String,
    ): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(mapOf("message" to message))

    private companion object {
        private val log = LoggerFactory.getLogger(PrivateNoteListExceptionHandler::class.java)
    }
}
