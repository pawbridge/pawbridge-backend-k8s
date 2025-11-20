package com.pawbridge.emailservice.exception;

import com.pawbridge.emailservice.exception.common.ApplicationException;
import com.pawbridge.emailservice.exception.common.ErrorCode;

public class InvalidCodeException extends ApplicationException {
    public InvalidCodeException() {
        super(ErrorCode.INVALID_CODE);
    }
}
