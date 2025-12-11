package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class FavoriteAlreadyExistsException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.FAVORITE_ALREADY_EXISTS;

    public FavoriteAlreadyExistsException() {
        super(ERROR_CODE);
    }
}
