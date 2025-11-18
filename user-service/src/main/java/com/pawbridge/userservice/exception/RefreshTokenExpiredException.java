package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class RefreshTokenExpiredException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.REFRESH_TOKEN_EXPIRED;

    public RefreshTokenExpiredException() {
        super(ERROR_CODE);
    }
}
