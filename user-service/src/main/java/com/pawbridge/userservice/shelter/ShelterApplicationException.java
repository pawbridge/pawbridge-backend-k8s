package com.pawbridge.userservice.shelter;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class ShelterApplicationException extends ApplicationException {
    public ShelterApplicationException(ErrorCode errorCode) {
        super(errorCode);
    }
}
