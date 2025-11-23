package com.pawbridge.userservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "email-service",
        url = "${email-service.url:http://localhost:8088}"
)
public interface EmailServiceClient {

    /**
     * 회원가입 이메일 인증 완료 여부 확인
     */
    @GetMapping("/internal/email/verified")
    Boolean isEmailVerified(@RequestParam("email") String email);

    /**
     * 회원가입 이메일 인증 정보 삭제
     */
    @DeleteMapping("/internal/email/verified")
    void clearVerification(@RequestParam("email") String email);

    /**
     * 비밀번호 재설정 인증코드 발송
     */
    @PostMapping("/internal/email/password-reset/send")
    void sendPasswordResetCode(@RequestParam("email") String email);

    /**
     * 비밀번호 재설정 인증코드 검증
     */
    @PostMapping("/internal/email/password-reset/verify")
    Boolean verifyPasswordResetCode(
            @RequestParam("email") String email,
            @RequestParam("code") String code);

    /**
     * 비밀번호 재설정 인증 정보 삭제
     */
    @DeleteMapping("/internal/email/password-reset/clear")
    void clearPasswordResetVerification(@RequestParam("email") String email);
}
