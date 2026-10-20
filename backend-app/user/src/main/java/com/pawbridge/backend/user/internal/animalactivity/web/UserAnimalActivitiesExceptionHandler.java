package com.pawbridge.backend.user.internal.animalactivity.web;

import com.pawbridge.backend.user.internal.animalactivity.application.FavoriteActivityRejectedException;
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController.Response;
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=UserAnimalActivitiesController.class)
public final class UserAnimalActivitiesExceptionHandler {
    private final UserNicknameExceptionHandler existing=new UserNicknameExceptionHandler();
    @ExceptionHandler(FavoriteActivityRejectedException.class) public ResponseEntity<Response<Void>> rejected(FavoriteActivityRejectedException failure){var reason=failure.reason();return ResponseEntity.status(reason.status()).body(new Response<>(reason.status(),reason.message(),null));}
    @ExceptionHandler(UserNotFoundException.class) public ResponseEntity<Response<Void>> missing(UserNotFoundException failure){return existing.userNotFound(failure);}
    @ExceptionHandler(RuntimeException.class) public ResponseEntity<Response<Void>> unexpected(RuntimeException failure){return existing.serverError(failure);}
}
