package com.pawbridge.animalservice.travel;

public final class PetTravelException extends RuntimeException {
    public enum Code { INVALID_REQUEST, NOT_FOUND, UNAVAILABLE }
    private final Code code;

    public PetTravelException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code getCode() { return code; }

    static PetTravelException unavailable() {
        return new PetTravelException(Code.UNAVAILABLE);
    }
}
