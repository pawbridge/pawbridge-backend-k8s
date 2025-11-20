package com.pawbridge.emailservice.exception;

import com.pawbridge.emailservice.exception.common.ApplicationException;
import com.pawbridge.emailservice.exception.common.ErrorCode;

public class EmailSendingException extends ApplicationException {
    public EmailSendingException() {
        super(ErrorCode.EMAIL_SENDING_FAILED);
    }

    public EmailSendingException(String message, Throwable cause) {
        super(ErrorCode.EMAIL_SENDING_FAILED, message);
        initCause(cause);
    }
}
