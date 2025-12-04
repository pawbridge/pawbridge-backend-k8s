package com.pawbridge.communityservice.exception;

import com.pawbridge.communityservice.exception.common.ApplicationException;
import com.pawbridge.communityservice.exception.common.ErrorCode;

public class S3UploadException extends ApplicationException {

    public S3UploadException() {
        super(ErrorCode.S3_UPLOAD_FAILED);
    }

    public S3UploadException(String message) {
        super(ErrorCode.S3_UPLOAD_FAILED, message);
    }
}
