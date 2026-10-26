package com.pawbridge.backend.user.internal.shelter.web

import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController.Response
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembershipRejectedException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(
    assignableTypes = [
        ShelterApplicationController::class,
        AdminShelterApplicationController::class,
        AdminShelterMemberController::class,
    ],
)
class ShelterMembershipExceptionHandler {

    private val existing = UserNicknameExceptionHandler()

    @ExceptionHandler(ShelterMembershipRejectedException::class)
    fun rejected(failure: ShelterMembershipRejectedException): ResponseEntity<Response<Void>> {
        val reason = failure.reason()
        return ResponseEntity.status(reason.status()).body(Response(reason.status(), reason.message(), null))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(failure: MethodArgumentNotValidException): ResponseEntity<Response<Void>> =
        existing.validation(failure)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(failure: HttpMessageNotReadableException): ResponseEntity<Response<Void>> =
        existing.unreadable(failure)

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missing(failure: MissingServletRequestParameterException): ResponseEntity<Response<Void>> =
        ResponseEntity.badRequest().body(
            Response(400, "필수 요청 파라미터 '" + failure.parameterName + "'(이)가 누락되었습니다.", null),
        )

    @ExceptionHandler(RuntimeException::class)
    fun unexpected(failure: RuntimeException): ResponseEntity<Response<Void>> = existing.serverError(failure)
}
