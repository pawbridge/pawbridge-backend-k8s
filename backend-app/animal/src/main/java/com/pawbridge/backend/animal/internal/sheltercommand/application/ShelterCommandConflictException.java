package com.pawbridge.backend.animal.internal.sheltercommand.application;

public final class ShelterCommandConflictException extends RuntimeException {
    public ShelterCommandConflictException() { super("연결된 동물 또는 회원이 있어 보호소를 삭제할 수 없습니다."); }
}
