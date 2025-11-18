package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class TokenInvalidException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.TOKEN_INVALID;

    public TokenInvalidException() {
        super(ERROR_CODE);
    }
}
