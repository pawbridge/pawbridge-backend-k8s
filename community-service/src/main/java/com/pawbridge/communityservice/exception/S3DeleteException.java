package com.pawbridge.communityservice.exception;

import com.pawbridge.communityservice.exception.common.ApplicationException;
import com.pawbridge.communityservice.exception.common.ErrorCode;

public class S3DeleteException extends ApplicationException {

    public S3DeleteException() {
        super(ErrorCode.S3_DELETE_FAILED);
    }

    public S3DeleteException(String message) {
        super(ErrorCode.S3_DELETE_FAILED, message);
    }
}
