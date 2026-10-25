package com.pawbridge.backend.animal.internal.image.infrastructure

/** Same message as the former Gateway non-USER path rejection. */
class AnimalImageForbiddenException : RuntimeException("권한이 부족합니다.")
