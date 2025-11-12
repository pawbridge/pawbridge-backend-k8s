package com.pawbridge.userservice.oauth2.exception;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * OAuth2 처리 중 발생하는 예외
 * Spring Security의 OAuth2AuthenticationException을 상속하여
 * OAuth2FailureHandler가 자동으로 처리할 수 있도록 함
 *
 * 사용 시나리오:
 * - 이메일 정보 없음
 * - 이메일 충돌 (LOCAL 계정이 이미 존재)
 * - 지원하지 않는 OAuth2 제공자
 * - DB 저장 실패
 */
public class OAuth2ProcessingException extends OAuth2AuthenticationException {

    public OAuth2ProcessingException(String message) {
        super(new OAuth2Error("oauth2_processing_error", message, null));
    }
}
