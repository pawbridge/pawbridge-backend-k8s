package com.pawbridge.backend.animal.internal.sheltercommand.application

class ShelterCommandForbiddenException : RuntimeException("보호소 정보 변경 권한이 없습니다.")
