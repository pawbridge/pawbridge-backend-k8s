package com.pawbridge.backend.animal.internal.image.infrastructure;

/** Same message as the former Gateway non-USER path rejection. */
public final class AnimalImageForbiddenException extends RuntimeException {
    public AnimalImageForbiddenException() { super("권한이 부족합니다."); }
}
