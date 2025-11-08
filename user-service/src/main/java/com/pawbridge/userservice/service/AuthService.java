package com.pawbridge.userservice.service;

import com.pawbridge.userservice.dto.request.RefreshTokenRequestDto;
import com.pawbridge.userservice.dto.respone.RefreshTokenResponseDto;

public interface AuthService {

    /**
     * Refresh Token을 사용하여 새로운 Access Token과 Refresh Token 발급
     */
    RefreshTokenResponseDto refreshToken(RefreshTokenRequestDto requestDto);

    /**
     * 로그아웃 - Refresh Token 삭제
     */
    void logout(Long userId);
}
