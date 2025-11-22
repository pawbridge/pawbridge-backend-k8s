package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class PasswordResetCodeInvalidException extends ApplicationException {
    public PasswordResetCodeInvalidException() {
        super(ErrorCode.PASSWORD_RESET_CODE_INVALID);
    }
}
