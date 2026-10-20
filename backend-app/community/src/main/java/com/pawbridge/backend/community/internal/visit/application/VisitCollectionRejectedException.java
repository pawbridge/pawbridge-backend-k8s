package com.pawbridge.backend.community.internal.visit.application;

public final class VisitCollectionRejectedException extends RuntimeException {
    public enum Reason { COOKIE, RANGE }
    private final Reason reason;
    public VisitCollectionRejectedException(Reason reason) {
        super(reason == Reason.COOKIE ? "방문 쿠키 초기화가 필요합니다." : "오늘까지 최대 366일을 조회할 수 있습니다.");
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
