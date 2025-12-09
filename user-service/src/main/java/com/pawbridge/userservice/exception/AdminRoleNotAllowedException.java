package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class AdminRoleNotAllowedException extends ApplicationException {
    public AdminRoleNotAllowedException() {
        super(ErrorCode.ADMIN_ROLE_NOT_ALLOWED);
    }
}
