package com.pawbridge.backend.animal.internal.shelter.application;

public final class ShelterNotFoundException extends RuntimeException {
    public ShelterNotFoundException() { super("보호소를 찾을 수 없습니다"); }
}
