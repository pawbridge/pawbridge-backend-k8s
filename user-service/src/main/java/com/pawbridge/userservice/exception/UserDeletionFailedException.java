package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class UserDeletionFailedException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.USER_DELETION_FAILED;

    public UserDeletionFailedException() {
        super(ERROR_CODE);
    }

    public UserDeletionFailedException(String message) {
        super(ERROR_CODE, message);
    }
}
