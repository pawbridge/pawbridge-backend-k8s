package com.pawbridge.animalservice.exception;

public final class SearchProjectionPendingException extends ApplicationException {
    public SearchProjectionPendingException() { super(ErrorCode.SEARCH_PROJECTION_PENDING); }
}
