package com.pawbridge.userservice.exception;


import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class EmailDuplicateException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.USER_ALREADY_REGISTERED;

    public EmailDuplicateException() {

        super(ERROR_CODE);
    }
}
