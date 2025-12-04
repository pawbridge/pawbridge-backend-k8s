package com.pawbridge.communityservice.exception;

import com.pawbridge.communityservice.exception.common.ApplicationException;
import com.pawbridge.communityservice.exception.common.ErrorCode;

public class InvalidImageFormatException extends ApplicationException {

    public InvalidImageFormatException() {
        super(ErrorCode.INVALID_IMAGE_FORMAT);
    }

    public InvalidImageFormatException(String message) {
        super(ErrorCode.INVALID_IMAGE_FORMAT, message);
    }
}
