package com.pawbridge.userservice.exception;

import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class ShelterCareRegNoRequiredException extends ApplicationException {
    public ShelterCareRegNoRequiredException() {
        super(ErrorCode.SHELTER_CARE_REG_NO_REQUIRED);
    }
}
