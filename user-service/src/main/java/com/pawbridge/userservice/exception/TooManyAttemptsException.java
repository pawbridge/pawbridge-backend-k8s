package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class TooManyAttemptsException extends ApplicationException {
    public TooManyAttemptsException(ErrorCode errorCode) {
        super(errorCode);
    }
}
