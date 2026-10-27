package com.pawbridge.backend.animal.internal.command.infrastructure.service

/** APMS 공고 동물은 다음 동기화가 덮어쓰므로 정보 수정을 받지 않는다. */
class AnimalNotEditableException : RuntimeException("보호소가 직접 등록한 동물만 수정할 수 있습니다.")
