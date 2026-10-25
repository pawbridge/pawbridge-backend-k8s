package com.pawbridge.backend.animal.internal.image.infrastructure

/** Same message as the original multipart size rejection. */
class AnimalImageTooLargeException : RuntimeException("사진 크기 제한을 초과했습니다")
