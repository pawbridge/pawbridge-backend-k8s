package com.pawbridge.communityservice.exception;

import com.pawbridge.communityservice.exception.common.ApplicationException;
import com.pawbridge.communityservice.exception.common.ErrorCode;

public class PostNotFoundException extends ApplicationException {

    public PostNotFoundException() {
        super(ErrorCode.POST_NOT_FOUND);
    }

    public PostNotFoundException(String message) {
        super(ErrorCode.POST_NOT_FOUND, message);
    }
}
