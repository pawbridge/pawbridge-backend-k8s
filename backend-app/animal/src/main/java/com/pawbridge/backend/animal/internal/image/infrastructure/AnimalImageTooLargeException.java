package com.pawbridge.backend.animal.internal.image.infrastructure;

/** Same message as the original multipart size rejection. */
public final class AnimalImageTooLargeException extends RuntimeException {
    public AnimalImageTooLargeException() { super("사진 크기 제한을 초과했습니다"); }
}
