package com.pawbridge.communityservice.exception;

import com.pawbridge.communityservice.exception.common.ApplicationException;
import com.pawbridge.communityservice.exception.common.ErrorCode;

public class UnauthorizedCommentAccessException extends ApplicationException {

    public UnauthorizedCommentAccessException() {
        super(ErrorCode.UNAUTHORIZED_COMMENT_ACCESS);
    }

    public UnauthorizedCommentAccessException(String message) {
        super(ErrorCode.UNAUTHORIZED_COMMENT_ACCESS, message);
    }
}
