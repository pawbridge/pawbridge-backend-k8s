package com.pawbridge.backend.user.internal.password.application

class SamePasswordException : RuntimeException("현재 비밀번호와 동일한 비밀번호로 변경할 수 없습니다.")
