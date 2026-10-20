package com.pawbridge.backend.animal.internal.discovery.application;

public final class SearchProjectionPendingException extends RuntimeException {
    public SearchProjectionPendingException() { super("검색 정보를 갱신 중입니다. 잠시 후 다시 시도해주세요"); }
}
