package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class SamePasswordException extends ApplicationException {
    public SamePasswordException() {
        super(ErrorCode.SAME_PASSWORD_NOT_ALLOWED);
    }
}
