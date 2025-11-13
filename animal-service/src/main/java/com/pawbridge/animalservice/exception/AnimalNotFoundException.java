package com.pawbridge.animalservice.exception;

/**
 * 동물을 찾을 수 없을 때 발생하는 예외
 */
public class AnimalNotFoundException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.ANIMAL_NOT_FOUND;

    public AnimalNotFoundException() {
        super(ERROR_CODE);
    }
}
