package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class FavoriteNotFoundException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.FAVORITE_NOT_FOUND;

    public FavoriteNotFoundException() {
        super(ERROR_CODE);
    }
}
