package com.pawbridge.backend.store.internal.catalog.application;

public final class CatalogSearchUnavailableException extends RuntimeException {
    public CatalogSearchUnavailableException() {
        super("검색 서비스를 준비 중입니다. 잠시 후 다시 시도해 주세요.");
    }
    public CatalogSearchUnavailableException(Throwable cause) {
        super("검색 서비스를 준비 중입니다. 잠시 후 다시 시도해 주세요.", cause);
    }
}
