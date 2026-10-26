package com.pawbridge.backend.user.internal.animalactivity.web

import com.pawbridge.backend.user.internal.animalactivity.application.FavoriteActivityRejectedException
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController.Response
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice(assignableTypes = [UserAnimalActivitiesController::class])
class UserAnimalActivitiesExceptionHandler {

    private val existing = UserNicknameExceptionHandler()

    @ExceptionHandler(FavoriteActivityRejectedException::class)
    fun rejected(failure: FavoriteActivityRejectedException): ResponseEntity<Response<Void>> {
        val reason = failure.reason()
        return ResponseEntity.status(reason.status()).body(Response(reason.status(), reason.message(), null))
    }

    @ExceptionHandler(UserNotFoundException::class)
    fun missing(failure: UserNotFoundException): ResponseEntity<Response<Void>> = existing.userNotFound(failure)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidPathVariable(failure: MethodArgumentTypeMismatchException): ResponseEntity<Response<Void>> =
        ResponseEntity.badRequest().body(Response(400, "요청 경로의 식별자 형식이 올바르지 않습니다.", null))

    @ExceptionHandler(RuntimeException::class)
    fun unexpected(failure: RuntimeException): ResponseEntity<Response<Void>> = existing.serverError(failure)
}
