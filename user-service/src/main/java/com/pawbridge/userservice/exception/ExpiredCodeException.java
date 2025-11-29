package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class ExpiredCodeException extends ApplicationException {
    public ExpiredCodeException() {
        super(ErrorCode.EXPIRED_CODE);
    }
}
