package com.pawbridge.backend.user.internal.adminupdate.application;

public final class ShelterApprovalRequiredException extends RuntimeException {
    public ShelterApprovalRequiredException() {
        super("보호소 권한은 담당자 신청 승인으로 부여해야 합니다.");
    }
}
