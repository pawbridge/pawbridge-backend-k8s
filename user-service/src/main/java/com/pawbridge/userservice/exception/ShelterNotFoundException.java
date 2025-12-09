package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class ShelterNotFoundException extends ApplicationException {
    public ShelterNotFoundException() {
        super(ErrorCode.SHELTER_NOT_FOUND);
    }
}
