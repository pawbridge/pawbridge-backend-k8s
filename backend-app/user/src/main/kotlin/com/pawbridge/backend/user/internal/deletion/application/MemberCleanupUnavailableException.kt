package com.pawbridge.backend.user.internal.deletion.application

class MemberCleanupUnavailableException : RuntimeException("쪽지 정리가 완료되지 않았습니다. 회원 삭제를 다시 시도해 주세요.")
