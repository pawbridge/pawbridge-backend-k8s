package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class EmailTemplateLoadException extends ApplicationException {
    public EmailTemplateLoadException() {
        super(ErrorCode.EMAIL_TEMPLATE_LOAD_FAILED);
    }
}
