package com.pawbridge.emailservice.exception;

import com.pawbridge.emailservice.exception.common.ApplicationException;
import com.pawbridge.emailservice.exception.common.ErrorCode;

public class EmailTemplateLoadException extends ApplicationException {
    public EmailTemplateLoadException() {
        super(ErrorCode.EMAIL_TEMPLATE_LOAD_FAILED);
    }
}
