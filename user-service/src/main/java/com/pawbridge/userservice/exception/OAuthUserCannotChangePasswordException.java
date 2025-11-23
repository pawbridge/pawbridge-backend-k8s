package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class OAuthUserCannotChangePasswordException extends ApplicationException {
    public OAuthUserCannotChangePasswordException() {
        super(ErrorCode.OAUTH_USER_CANNOT_CHANGE_PASSWORD);
    }
}
