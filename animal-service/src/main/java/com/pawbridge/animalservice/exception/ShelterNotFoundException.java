package com.pawbridge.animalservice.exception;

/**
 * 보호소를 찾을 수 없을 때 발생하는 예외
 */
public class ShelterNotFoundException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.SHELTER_NOT_FOUND;

    public ShelterNotFoundException() {
        super(ERROR_CODE);
    }
}
