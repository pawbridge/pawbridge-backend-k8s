package com.pawbridge.communityservice.exception;

import com.pawbridge.communityservice.exception.common.ApplicationException;
import com.pawbridge.communityservice.exception.common.ErrorCode;

public class UnauthorizedPostAccessException extends ApplicationException {

    public UnauthorizedPostAccessException() {
        super(ErrorCode.UNAUTHORIZED_POST_ACCESS);
    }

    public UnauthorizedPostAccessException(String message) {
        super(ErrorCode.UNAUTHORIZED_POST_ACCESS, message);
    }
}
