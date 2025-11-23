package com.pawbridge.userservice.service;

import com.pawbridge.userservice.client.EmailServiceClient;
import com.pawbridge.userservice.dto.request.PasswordResetRequestDto;
import com.pawbridge.userservice.dto.request.PasswordResetVerifyDto;
import com.pawbridge.userservice.dto.request.RefreshTokenRequestDto;
import com.pawbridge.userservice.dto.respone.RefreshTokenResponseDto;
import com.pawbridge.userservice.entity.RefreshToken;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.exception.PasswordResetCodeInvalidException;
import com.pawbridge.userservice.exception.RefreshTokenExpiredException;
import com.pawbridge.userservice.exception.RefreshTokenNotFoundException;
import com.pawbridge.userservice.exception.TokenInvalidException;
import com.pawbridge.userservice.exception.UserNotFoundException;
import com.pawbridge.userservice.jwt.JwtProvider;
import com.pawbridge.userservice.repository.RefreshTokenRepository;
import com.pawbridge.userservice.repository.UserRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthServiceImpl implements AuthService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;
    private final EmailServiceClient emailServiceClient;

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

    /**
     * 비밀번호 재설정 요청 (이메일 발송)
     */
    @Override
    @Transactional(readOnly = true)
    public void requestPasswordReset(PasswordResetRequestDto requestDto) {
        // LOCAL 사용자만 조회 (OAuth2 사용자 제외)
        Optional<User> userOpt = userRepository.findByEmailAndProvider(
                requestDto.getEmail(), "LOCAL");

        if (userOpt.isPresent()) {
            try {
                // 이메일 발송
                emailServiceClient.sendPasswordResetCode(requestDto.getEmail());
                log.info("비밀번호 재설정 이메일 발송 성공: {}", requestDto.getEmail());
            } catch (FeignException e) {
                log.error("이메일 발송 실패: {}", e.getMessage());
                // 보안상 실패해도 성공 응답 (계정 존재 여부 노출 방지)
            }
        }
        // 이메일이 없어도 동일하게 성공 응답 (보안)
        log.debug("비밀번호 재설정 요청 처리 완료: {}", requestDto.getEmail());
    }

    /**
     * 비밀번호 재설정 (인증 후 변경)
     */
    @Override
    @Transactional
    public void resetPassword(PasswordResetVerifyDto requestDto) {
        // 1. LOCAL 사용자만 조회 (보안: 존재하지 않아도 동일한 예외)
        User user = userRepository.findByEmailAndProvider(
                requestDto.getEmail(), "LOCAL")
                .orElseThrow(() -> new PasswordResetCodeInvalidException());

        // 2. 인증코드 검증
        try {
            Boolean verified = emailServiceClient.verifyPasswordResetCode(
                    requestDto.getEmail(),
                    requestDto.getCode());

            if (!Boolean.TRUE.equals(verified)) {
                throw new PasswordResetCodeInvalidException();
            }
        } catch (FeignException e) {
            log.error("인증코드 검증 실패: {}", e.getMessage());
            throw new PasswordResetCodeInvalidException();
        }

        // 3. 비밀번호 암호화 및 변경
        String encodedPassword = passwordEncoder.encode(requestDto.getNewPassword());
        user.updatePassword(encodedPassword);
        userRepository.save(user);

        // 4. 인증 정보 삭제 (실패해도 계속 진행)
        try {
            emailServiceClient.clearPasswordResetVerification(requestDto.getEmail());
        } catch (FeignException e) {
            log.warn("인증 정보 삭제 실패 (무시): {}", e.getMessage());
        }

        log.info("비밀번호 재설정 완료: {}", requestDto.getEmail());
    }
}
