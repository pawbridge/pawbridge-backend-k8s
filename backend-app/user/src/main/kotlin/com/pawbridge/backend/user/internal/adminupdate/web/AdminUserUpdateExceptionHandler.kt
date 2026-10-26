package com.pawbridge.backend.user.internal.adminupdate.web

import com.pawbridge.backend.user.internal.adminupdate.application.ShelterApprovalRequiredException
import com.pawbridge.backend.user.internal.nickname.application.NicknameDuplicateException
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice(assignableTypes = [AdminUserUpdateController::class])
class AdminUserUpdateExceptionHandler {

    private val existing = UserNicknameExceptionHandler()

    @ExceptionHandler(UserNotFoundException::class)
    fun missing(failure: UserNotFoundException): ResponseEntity<UserNicknameController.Response<Void>> =
        existing.userNotFound(failure)

    @ExceptionHandler(NicknameDuplicateException::class)
    fun duplicate(failure: NicknameDuplicateException): ResponseEntity<UserNicknameController.Response<Void>> =
        existing.duplicate(failure)

    @ExceptionHandler(ShelterApprovalRequiredException::class)
    fun approval(failure: ShelterApprovalRequiredException): ResponseEntity<UserNicknameController.Response<Void>> =
        ResponseEntity.badRequest().body(
            UserNicknameController.Response<Void>(400, "보호소 권한은 담당자 신청 승인으로 부여해야 합니다.", null),
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(failure: MethodArgumentNotValidException): ResponseEntity<UserNicknameController.Response<Void>> =
        existing.validation(failure)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(failure: HttpMessageNotReadableException): ResponseEntity<UserNicknameController.Response<Void>> =
        existing.unreadable(failure)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidPathVariable(
        failure: MethodArgumentTypeMismatchException,
    ): ResponseEntity<UserNicknameController.Response<Void>> =
        ResponseEntity.badRequest().body(UserNicknameController.Response<Void>(400, "요청 경로의 식별자 형식이 올바르지 않습니다.", null))

    @ExceptionHandler(RuntimeException::class)
    fun unexpected(failure: RuntimeException): ResponseEntity<UserNicknameController.Response<Void>> =
        existing.serverError(failure)
}
