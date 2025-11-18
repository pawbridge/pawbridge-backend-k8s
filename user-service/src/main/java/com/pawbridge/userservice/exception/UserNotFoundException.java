package com.pawbridge.userservice.exception;


import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class UserNotFoundException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.USER_NOT_FOUND;

    public UserNotFoundException() {
        super(ERROR_CODE);
    }
}
