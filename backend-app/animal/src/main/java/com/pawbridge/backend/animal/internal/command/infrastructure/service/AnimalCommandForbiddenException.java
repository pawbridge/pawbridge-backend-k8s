package com.pawbridge.backend.animal.internal.command.infrastructure.service;
public final class AnimalCommandForbiddenException extends RuntimeException {
    public AnimalCommandForbiddenException() { super("동물 변경 권한이 없습니다."); }
}
