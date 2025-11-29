package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class InvalidCodeException extends ApplicationException {
    public InvalidCodeException() {
        super(ErrorCode.INVALID_CODE);
    }
}
