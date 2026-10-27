package com.pawbridge.backend.animal.internal.command.infrastructure.service

/** APMS 공고 동물은 다음 동기화가 다시 만들고 챗봇 기록이 외래키로 묶여 있어 삭제를 받지 않는다. */
class AnimalNotDeletableException : RuntimeException("보호소가 직접 등록한 동물만 삭제할 수 있습니다.")
