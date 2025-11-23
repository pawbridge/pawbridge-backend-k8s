package com.pawbridge.emailservice.service;

import com.pawbridge.emailservice.exception.ExpiredCodeException;
import com.pawbridge.emailservice.exception.InvalidCodeException;
import com.pawbridge.emailservice.exception.TooManyAttemptsException;
import com.pawbridge.emailservice.exception.common.ErrorCode;
import com.pawbridge.emailservice.util.CodeGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final RedisTemplate<String, String> redisTemplate;
    private final EmailSenderService emailSenderService;
    private final RedisScript<Long> checkAndIncrementScript;
    private final RedisScript<Long> incrementWithExpireScript;

    private static final int CODE_LENGTH = 6;
    private static final long CODE_EXPIRATION_MINUTES = 5;
    private static final long VERIFIED_EXPIRATION_HOURS = 1;
    private static final int MAX_SEND_ATTEMPTS = 5;
    private static final int MAX_VERIFY_ATTEMPTS = 5;

    /**
     * 인증 코드 발송
     */
    public void sendVerificationCode(String email) {
        // 1. 발송 횟수 제한 체크 및 증가 (원자적)
        checkAndIncrementSendCount(email);

        // 2. 6자리 숫자 코드 생성
        String code = CodeGenerator.generateNumeric(CODE_LENGTH);

        // 3. 이메일 발송 먼저 (실패하면 예외 발생, Redis 저장 안 됨)
        emailSenderService.sendVerificationEmail(email, code);

        // 4. 발송 성공 후 Redis에 저장
        String codeKey = "email:code:" + email;
        redisTemplate.opsForValue().set(codeKey, code, CODE_EXPIRATION_MINUTES, TimeUnit.MINUTES);

        // 5. 검증 시도 횟수 초기화
        String attemptsKey = "email:attempts:" + email;
        redisTemplate.delete(attemptsKey);
    }

    /**
     * 인증 코드 검증
     */
    public boolean verifyCode(String email, String code) {
        // 1. 검증 시도 횟수 체크
        checkVerifyRateLimit(email);

        // 2. 저장된 코드 조회
        String codeKey = "email:code:" + email;
        String storedCode = redisTemplate.opsForValue().get(codeKey);

        if (storedCode == null) {
            throw new ExpiredCodeException();
        }

        // 3. 코드 검증
        if (!storedCode.equals(code)) {
            incrementVerifyAttempts(email);
            throw new InvalidCodeException();
        }

        // 4. 인증 성공 처리
        String verifiedKey = "email:verified:" + email;
        redisTemplate.opsForValue().set(verifiedKey, "true", VERIFIED_EXPIRATION_HOURS, TimeUnit.HOURS);

        redisTemplate.delete(codeKey);

        String attemptsKey = "email:attempts:" + email;
        redisTemplate.delete(attemptsKey);

        return true;
    }

    /**
     * 인증 여부 확인
     */
    public boolean isVerified(String email) {
        String verifiedKey = "email:verified:" + email;
        String verified = redisTemplate.opsForValue().get(verifiedKey);
        return "true".equals(verified);
    }

    /**
     * 인증 완료 상태 삭제 (회원가입 후 호출)
     */
    public void clearVerification(String email) {
        String verifiedKey = "email:verified:" + email;
        redisTemplate.delete(verifiedKey);
    }

    // ========== 비밀번호 재설정 관련 메서드 ==========

    /**
     * 비밀번호 재설정 인증 코드 발송
     */
    public void sendPasswordResetCode(String email) {
        // 1. 발송 횟수 제한 체크 및 증가 (원자적)
        checkAndIncrementPasswordResetSendCount(email);

        // 2. 6자리 숫자 코드 생성
        String code = CodeGenerator.generateNumeric(CODE_LENGTH);

        // 3. 이메일 발송 먼저 (실패하면 예외 발생, Redis 저장 안 됨)
        emailSenderService.sendPasswordResetEmail(email, code);

        // 4. 발송 성공 후 Redis에 저장
        String codeKey = "password:reset:code:" + email;
        redisTemplate.opsForValue().set(codeKey, code, CODE_EXPIRATION_MINUTES, TimeUnit.MINUTES);

        // 5. 검증 시도 횟수 초기화
        String attemptsKey = "password:reset:attempts:" + email;
        redisTemplate.delete(attemptsKey);
    }

    /**
     * 비밀번호 재설정 인증 코드 검증
     */
    public boolean verifyPasswordResetCode(String email, String code) {
        // 1. 검증 시도 횟수 체크
        checkPasswordResetVerifyRateLimit(email);

        // 2. 저장된 코드 조회
        String codeKey = "password:reset:code:" + email;
        String storedCode = redisTemplate.opsForValue().get(codeKey);

        if (storedCode == null) {
            throw new ExpiredCodeException();
        }

        // 3. 코드 검증
        if (!storedCode.equals(code)) {
            incrementPasswordResetVerifyAttempts(email);
            throw new InvalidCodeException();
        }

        // 4. 인증 성공 처리
        String verifiedKey = "password:reset:verified:" + email;
        redisTemplate.opsForValue().set(verifiedKey, "true", VERIFIED_EXPIRATION_HOURS, TimeUnit.HOURS);

        redisTemplate.delete(codeKey);

        String attemptsKey = "password:reset:attempts:" + email;
        redisTemplate.delete(attemptsKey);

        return true;
    }

    /**
     * 비밀번호 재설정 인증 정보 삭제
     */
    public void clearPasswordResetVerification(String email) {
        String verifiedKey = "password:reset:verified:" + email;
        redisTemplate.delete(verifiedKey);
    }

    /**
     * 비밀번호 재설정 발송 횟수 체크 및 증가 (원자적)
     */
    private void checkAndIncrementPasswordResetSendCount(String email) {
        String sendCountKey = "password:reset:send:count:" + email;

        Long result = redisTemplate.execute(
                checkAndIncrementScript,
                Collections.singletonList(sendCountKey),
                String.valueOf(MAX_SEND_ATTEMPTS),
                String.valueOf(CODE_EXPIRATION_MINUTES * 60)
        );

        if (result != null && result == -1) {
            throw new TooManyAttemptsException(ErrorCode.TOO_MANY_SEND_ATTEMPTS);
        }
    }

    /**
     * 비밀번호 재설정 검증 시도 횟수 체크
     */
    private void checkPasswordResetVerifyRateLimit(String email) {
        String attemptsKey = "password:reset:attempts:" + email;
        String attempts = redisTemplate.opsForValue().get(attemptsKey);

        if (attempts != null && Integer.parseInt(attempts) >= MAX_VERIFY_ATTEMPTS) {
            throw new TooManyAttemptsException(ErrorCode.TOO_MANY_VERIFY_ATTEMPTS);
        }
    }

    /**
     * 비밀번호 재설정 검증 시도 횟수 증가 (원자적)
     */
    private void incrementPasswordResetVerifyAttempts(String email) {
        String attemptsKey = "password:reset:attempts:" + email;
        redisTemplate.execute(
                incrementWithExpireScript,
                Collections.singletonList(attemptsKey),
                String.valueOf(CODE_EXPIRATION_MINUTES * 60)
        );
    }

    /**
     * 발송 횟수 체크 및 증가 (원자적)
     */
    private void checkAndIncrementSendCount(String email) {
        String sendCountKey = "email:send:count:" + email;

        Long result = redisTemplate.execute(
                checkAndIncrementScript,
                Collections.singletonList(sendCountKey),
                String.valueOf(MAX_SEND_ATTEMPTS),
                String.valueOf(CODE_EXPIRATION_MINUTES * 60)
        );

        if (result != null && result == -1) {
            throw new TooManyAttemptsException(ErrorCode.TOO_MANY_SEND_ATTEMPTS);
        }
    }

    /**
     * 검증 시도 횟수 체크
     */
    private void checkVerifyRateLimit(String email) {
        String attemptsKey = "email:attempts:" + email;
        String attempts = redisTemplate.opsForValue().get(attemptsKey);

        if (attempts != null && Integer.parseInt(attempts) >= MAX_VERIFY_ATTEMPTS) {
            throw new TooManyAttemptsException(ErrorCode.TOO_MANY_VERIFY_ATTEMPTS);
        }
    }

    /**
     * 검증 시도 횟수 증가 (원자적)
     */
    private void incrementVerifyAttempts(String email) {
        String attemptsKey = "email:attempts:" + email;
        redisTemplate.execute(
                incrementWithExpireScript,
                Collections.singletonList(attemptsKey),
                String.valueOf(CODE_EXPIRATION_MINUTES * 60)
        );
    }
}
