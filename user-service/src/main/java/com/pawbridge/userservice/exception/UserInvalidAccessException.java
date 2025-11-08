package com.pawbridge.userservice.exception;


import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;
public class UserInvalidAccessException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.USER_INVALID_ACCESS;

    public UserInvalidAccessException() {
        super(ERROR_CODE);
    }
}
