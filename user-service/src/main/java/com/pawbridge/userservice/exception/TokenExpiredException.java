package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class TokenExpiredException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.TOKEN_EXPIRED;

    public TokenExpiredException() {
        super(ERROR_CODE);
    }
}
