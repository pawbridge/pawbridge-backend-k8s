package com.pawbridge.storeservice.search;
public class SearchUnavailableException extends RuntimeException {
    public SearchUnavailableException() { super("검색 서비스를 준비 중입니다. 잠시 후 다시 시도해 주세요."); }
}
