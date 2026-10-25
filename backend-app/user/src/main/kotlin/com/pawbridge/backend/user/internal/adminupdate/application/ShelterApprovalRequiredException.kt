package com.pawbridge.backend.user.internal.adminupdate.application

class ShelterApprovalRequiredException : RuntimeException("보호소 권한은 담당자 신청 승인으로 부여해야 합니다.")
