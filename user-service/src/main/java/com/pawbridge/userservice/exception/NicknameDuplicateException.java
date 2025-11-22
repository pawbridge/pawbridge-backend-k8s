package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class NicknameDuplicateException extends ApplicationException {
    public NicknameDuplicateException() {
        super(ErrorCode.NICKNAME_DUPLICATE);
    }
}
