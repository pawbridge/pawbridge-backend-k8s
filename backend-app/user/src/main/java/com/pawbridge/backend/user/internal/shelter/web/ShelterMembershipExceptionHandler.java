package com.pawbridge.backend.user.internal.shelter.web;

import com.pawbridge.backend.user.internal.shelter.application.ShelterMembershipRejectedException;
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler;
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController.Response;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes={ShelterApplicationController.class,AdminShelterApplicationController.class,AdminShelterMemberController.class})
public final class ShelterMembershipExceptionHandler {
    private final UserNicknameExceptionHandler existing=new UserNicknameExceptionHandler();
    @ExceptionHandler(ShelterMembershipRejectedException.class) public ResponseEntity<Response<Void>> rejected(ShelterMembershipRejectedException failure){
        var reason=failure.reason();return ResponseEntity.status(reason.status()).body(new Response<>(reason.status(),reason.message(),null));}
    @ExceptionHandler(MethodArgumentNotValidException.class) public ResponseEntity<Response<Void>> validation(MethodArgumentNotValidException failure){return existing.validation(failure);}
    @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<Response<Void>> unreadable(HttpMessageNotReadableException failure){return existing.unreadable(failure);}
    @ExceptionHandler(MissingServletRequestParameterException.class) public ResponseEntity<Response<Void>> missing(MissingServletRequestParameterException failure){return ResponseEntity.badRequest()
            .body(new Response<>(400,"필수 요청 파라미터 '"+failure.getParameterName()+"'(이)가 누락되었습니다.",null));}
    @ExceptionHandler(RuntimeException.class) public ResponseEntity<Response<Void>> unexpected(RuntimeException failure){return existing.serverError(failure);}
}
