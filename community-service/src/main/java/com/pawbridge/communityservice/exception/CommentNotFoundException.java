package com.pawbridge.communityservice.exception;

import com.pawbridge.communityservice.exception.common.ApplicationException;
import com.pawbridge.communityservice.exception.common.ErrorCode;

public class CommentNotFoundException extends ApplicationException {

    public CommentNotFoundException() {
        super(ErrorCode.COMMENT_NOT_FOUND);
    }

    public CommentNotFoundException(String message) {
        super(ErrorCode.COMMENT_NOT_FOUND, message);
    }
}
