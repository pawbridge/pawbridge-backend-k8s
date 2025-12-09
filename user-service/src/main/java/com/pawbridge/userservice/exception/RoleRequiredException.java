package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class RoleRequiredException extends ApplicationException {
    public RoleRequiredException() {
        super(ErrorCode.ROLE_REQUIRED);
    }
}
