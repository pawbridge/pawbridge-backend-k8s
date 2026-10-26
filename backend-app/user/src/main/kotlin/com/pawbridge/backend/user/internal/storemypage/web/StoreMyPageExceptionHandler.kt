package com.pawbridge.backend.user.internal.storemypage.web

import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController.Response
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [StoreMyPageController::class])
class StoreMyPageExceptionHandler {

    private val existing = UserNicknameExceptionHandler()

    @ExceptionHandler(UserNotFoundException::class)
    fun missing(failure: UserNotFoundException): ResponseEntity<Response<Void>> = existing.userNotFound(failure)

    @ExceptionHandler(RuntimeException::class)
    fun unavailable(failure: RuntimeException): ResponseEntity<Response<Void>> = existing.serverError(failure)
}
