package com.pawbridge.backend.animal.internal.sheltercommand.application;

public final class ShelterCommandForbiddenException extends RuntimeException {
    public ShelterCommandForbiddenException() { super("보호소 정보 변경 권한이 없습니다."); }
}
