package com.pawbridge.userservice.oauth2.dto;

/**
 * OAuth2 제공자별 사용자 정보를 추상화하는 인터페이스
 * OCP(Open-Closed Principle) 원칙을 준수하여 향후 Kakao, Naver 등 다른 제공자 추가 시 확장 용이
 */
public interface OAuth2UserInfo {

    /**
     * OAuth2 제공자의 고유 사용자 ID
     * (Google의 경우 "sub" 필드 값)
     */
    String getProviderId();

    /**
     * 사용자 이메일
     */
    String getEmail();

    /**
     * 사용자 이름
     */
    String getName();

    /**
     * OAuth2 제공자 이름
     * (예: "GOOGLE", "KAKAO", "NAVER")
     */
    String getProvider();
}
