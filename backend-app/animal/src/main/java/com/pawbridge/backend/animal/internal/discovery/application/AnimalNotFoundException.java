package com.pawbridge.backend.animal.internal.discovery.application;

public final class AnimalNotFoundException extends RuntimeException {
    public AnimalNotFoundException() { super("동물을 찾을 수 없습니다"); }
}
