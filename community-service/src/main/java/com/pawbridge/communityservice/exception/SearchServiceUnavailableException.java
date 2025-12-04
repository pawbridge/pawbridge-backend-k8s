package com.pawbridge.communityservice.exception;

import com.pawbridge.communityservice.exception.common.ApplicationException;
import com.pawbridge.communityservice.exception.common.ErrorCode;

public class SearchServiceUnavailableException extends ApplicationException {

    public SearchServiceUnavailableException() {
        super(ErrorCode.SEARCH_SERVICE_UNAVAILABLE);
    }

    public SearchServiceUnavailableException(String message) {
        super(ErrorCode.SEARCH_SERVICE_UNAVAILABLE, message);
    }
}
