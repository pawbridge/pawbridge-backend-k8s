package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class EmailServiceUnavailableException extends ApplicationException {
    public EmailServiceUnavailableException() {
        super(ErrorCode.EMAIL_SERVICE_UNAVAILABLE);
    }
}
