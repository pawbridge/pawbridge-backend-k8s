package com.pawbridge.backend.user.internal.shelter.application;

public final class ShelterMembershipRejectedException extends RuntimeException {
    public enum Reason {
        TOKEN_INVALID(401,"유효하지 않은 토큰입니다."), FORBIDDEN(403,"보호소 신청을 처리할 권한이 없습니다."),
        NOT_FOUND(404,"보호소 신청을 찾을 수 없습니다."),
        CONFLICT(409,"이미 대기 중이거나 처리된 신청입니다. 상태를 다시 확인해주세요."),
        INVALID(400,"신청 이름, 등록번호 또는 처리 사유를 확인해주세요."),
        INVALID_INPUT(400,"입력값이 올바르지 않습니다."), SHELTER_NOT_FOUND(400,"존재하지 않는 보호소 등록번호입니다."),
        SHELTER_UNAVAILABLE(503,"보호소 정보를 확인할 수 없습니다. 잠시 후 다시 시도해주세요.");
        private final int status; private final String message;
        Reason(int status,String message){this.status=status;this.message=message;}
        public int status(){return status;} public String message(){return message;}
    }
    private final Reason reason;
    public ShelterMembershipRejectedException(Reason reason){super(reason.message());this.reason=reason;}
    public Reason reason(){return reason;}
}
