package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class ShelterServiceUnavailableException extends ApplicationException {
    public ShelterServiceUnavailableException() {
        super(ErrorCode.SHELTER_SERVICE_UNAVAILABLE);
    }
}
