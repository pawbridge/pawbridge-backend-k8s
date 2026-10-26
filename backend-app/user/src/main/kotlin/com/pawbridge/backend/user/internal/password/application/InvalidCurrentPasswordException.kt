package com.pawbridge.backend.user.internal.password.application

class InvalidCurrentPasswordException : RuntimeException("현재 비밀번호가 일치하지 않습니다.")
