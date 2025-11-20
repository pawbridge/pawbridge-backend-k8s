package com.pawbridge.emailservice.exception;

import com.pawbridge.emailservice.exception.common.ApplicationException;
import com.pawbridge.emailservice.exception.common.ErrorCode;

public class TooManyAttemptsException extends ApplicationException {
    public TooManyAttemptsException(ErrorCode errorCode) {
        super(errorCode);
    }
}
