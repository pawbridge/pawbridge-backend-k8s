package com.pawbridge.userservice.service;

import com.pawbridge.userservice.dto.request.RefreshTokenRequestDto;
import com.pawbridge.userservice.dto.respone.RefreshTokenResponseDto;
import com.pawbridge.userservice.entity.RefreshToken;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.exception.RefreshTokenExpiredException;
import com.pawbridge.userservice.exception.RefreshTokenNotFoundException;
import com.pawbridge.userservice.exception.TokenInvalidException;
import com.pawbridge.userservice.exception.UserNotFoundException;
import com.pawbridge.userservice.jwt.JwtProvider;
import com.pawbridge.userservice.repository.RefreshTokenRepository;
import com.pawbridge.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthServiceImpl implements AuthService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;

    /**
     * Refresh Token을 사용하여 새로운 Access Token과 Refresh Token 발급
     */
    @Override
    @Transactional
    public RefreshTokenResponseDto refreshToken(RefreshTokenRequestDto requestDto) {
        String refreshTokenValue = requestDto.refreshToken();

        // 1. Refresh Token JWT 유효성 검증
        if (!jwtProvider.validateRefreshToken(refreshTokenValue)) {
            throw new TokenInvalidException();
        }

        // 2. DB에서 Refresh Token 조회
        RefreshToken refreshToken = refreshTokenRepository.findByToken(refreshTokenValue)
                .orElseThrow(RefreshTokenNotFoundException::new);

        // 3. Refresh Token 만료 여부 확인
        if (refreshToken.isExpired()) {
            refreshTokenRepository.delete(refreshToken);
            throw new RefreshTokenExpiredException();
        }

        // 4. 사용자 정보 조회
        User user = userRepository.findById(refreshToken.getUserId())
                .orElseThrow(UserNotFoundException::new);

        // 5. 새로운 Access Token 생성
        String newAccessToken = jwtProvider.createAccessToken(user);

        // 6. 새로운 Refresh Token 생성
        String newRefreshToken = jwtProvider.createRefreshToken();

        // 7. DB의 Refresh Token 업데이트
        long refreshTokenExpirationMs = jwtProvider.getRefreshTokenExpiration();
        LocalDateTime newExpiresAt = LocalDateTime.now()
                .plusSeconds(TimeUnit.MILLISECONDS.toSeconds(refreshTokenExpirationMs));

        refreshToken.updateToken(newRefreshToken, newExpiresAt);
        refreshTokenRepository.save(refreshToken);

        // 8. 응답 반환
        return new RefreshTokenResponseDto(newAccessToken, newRefreshToken);
    }

    /**
     * 로그아웃 - Refresh Token 삭제
     */
    @Override
    @Transactional
    public void logout(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }
}
