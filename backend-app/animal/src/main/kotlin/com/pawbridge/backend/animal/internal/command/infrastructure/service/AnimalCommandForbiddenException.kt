package com.pawbridge.backend.animal.internal.command.infrastructure.service

class AnimalCommandForbiddenException : RuntimeException("동물 변경 권한이 없습니다.")
