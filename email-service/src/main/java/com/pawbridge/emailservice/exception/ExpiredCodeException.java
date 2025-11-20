package com.pawbridge.emailservice.exception;

import com.pawbridge.emailservice.exception.common.ApplicationException;
import com.pawbridge.emailservice.exception.common.ErrorCode;

public class ExpiredCodeException extends ApplicationException {
    public ExpiredCodeException() {
        super(ErrorCode.EXPIRED_CODE);
    }
}
