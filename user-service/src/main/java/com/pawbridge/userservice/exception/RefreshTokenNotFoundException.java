package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class RefreshTokenNotFoundException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.REFRESH_TOKEN_NOT_FOUND;

    public RefreshTokenNotFoundException() {
        super(ERROR_CODE);
    }
}
