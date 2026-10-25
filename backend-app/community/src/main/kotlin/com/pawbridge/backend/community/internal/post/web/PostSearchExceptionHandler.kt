package com.pawbridge.backend.community.internal.post.web

import com.pawbridge.backend.community.internal.post.application.InvalidPostSearchInputException
import com.pawbridge.backend.community.internal.post.application.PostSearchUnavailableException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [PostSearchController::class])
class PostSearchExceptionHandler {

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingKeyword(failure: MissingServletRequestParameterException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.badRequest().body(
            ApiResponse<Void>(
                400,
                "필수 요청 파라미터 '" + failure.parameterName + "'(이)가 누락되었습니다.",
                null,
            ),
        )

    @ExceptionHandler(InvalidPostSearchInputException::class)
    fun invalidInput(failure: InvalidPostSearchInputException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.badRequest().body(ApiResponse<Void>(400, "검색 입력을 확인해 주세요.", null))

    @ExceptionHandler(PostSearchUnavailableException::class)
    fun unavailable(failure: PostSearchUnavailableException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiResponse<Void>(503, "검색 서비스를 사용할 수 없습니다.", null))

    @ExceptionHandler(RuntimeException::class)
    fun serverError(failure: RuntimeException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse<Void>(500, "서버 에러!", null))
}
