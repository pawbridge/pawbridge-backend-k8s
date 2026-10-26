package com.pawbridge.backend.user.internal.deletion.web

import com.pawbridge.backend.user.internal.deletion.application.MemberCleanupUnavailableException
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice(assignableTypes = [AdminUserDeletionController::class])
class AdminUserDeletionExceptionHandler {

    @ExceptionHandler(UserNotFoundException::class)
    fun missing(failure: UserNotFoundException): ResponseEntity<AdminUserDeletionController.Response<Void>> =
        response(400, "존재하지 않는 회원입니다.")

    @ExceptionHandler(MemberCleanupUnavailableException::class)
    fun cleanupUnavailable(
        failure: MemberCleanupUnavailableException,
    ): ResponseEntity<AdminUserDeletionController.Response<Void>> =
        response(503, "쪽지 정리가 완료되지 않았습니다. 회원 삭제를 다시 시도해 주세요.")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidPathVariable(
        failure: MethodArgumentTypeMismatchException,
    ): ResponseEntity<AdminUserDeletionController.Response<Void>> =
        response(400, "요청 경로의 식별자 형식이 올바르지 않습니다.")

    @ExceptionHandler(RuntimeException::class)
    fun unexpected(failure: RuntimeException): ResponseEntity<AdminUserDeletionController.Response<Void>> {
        LOG.error("Admin user deletion failed ({})", failure.javaClass.simpleName)
        return response(500, "서버 에러!")
    }

    private fun response(status: Int, message: String): ResponseEntity<AdminUserDeletionController.Response<Void>> =
        ResponseEntity.status(status).body(AdminUserDeletionController.Response(status, message, null))

    private companion object {
        private val LOG = LoggerFactory.getLogger(AdminUserDeletionExceptionHandler::class.java)
    }
}
