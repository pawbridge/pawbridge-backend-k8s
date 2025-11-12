package com.pawbridge.userservice.oauth2.dto;

import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * Google OAuth2 사용자 정보 구현체
 *
 * Google OAuth2 응답 예시:
 * {
 *   "sub": "10769150350006150715113082367",
 *   "email": "test@gmail.com",
 *   "name": "홍길동",
 *   "picture": "https://...",
 *   "email_verified": true
 * }
 */
@RequiredArgsConstructor
public class GoogleOAuth2UserInfo implements OAuth2UserInfo {

    private final Map<String, Object> attributes;

    @Override
    public String getProviderId() {
        return (String) attributes.get("sub");
    }

    @Override
    public String getEmail() {
        return (String) attributes.get("email");
    }

    @Override
    public String getName() {
        return (String) attributes.get("name");
    }

    @Override
    public String getProvider() {
        return "GOOGLE";
    }
}
