package com.pawbridge.emailservice.controller;

import com.pawbridge.emailservice.dto.request.SendVerificationCodeRequest;
import com.pawbridge.emailservice.dto.request.VerifyCodeRequest;
import com.pawbridge.emailservice.dto.response.EmailVerifiedResponse;
import com.pawbridge.emailservice.service.EmailVerificationService;
import com.pawbridge.emailservice.util.ResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class EmailController {

    private final EmailVerificationService emailVerificationService;

    /**
     * 인증 코드 발송
     */
    @PostMapping("/api/email/send")
    public ResponseEntity<ResponseDTO<Void>> sendVerificationCode(
            @Valid @RequestBody SendVerificationCodeRequest request) {

        emailVerificationService.sendVerificationCode(request.email());

        return ResponseEntity.ok(
                ResponseDTO.okWithMessage("인증 코드가 발송되었습니다.")
        );
    }

    /**
     * 인증 코드 검증
     */
    @PostMapping("/api/email/verify")
    public ResponseEntity<ResponseDTO<EmailVerifiedResponse>> verifyCode(
            @Valid @RequestBody VerifyCodeRequest request) {

        boolean verified = emailVerificationService.verifyCode(
                request.email(),
                request.code()
        );

        return ResponseEntity.ok(
                ResponseDTO.okWithData(
                        new EmailVerifiedResponse(verified),
                        "이메일 인증에 성공했습니다."
                )
        );
    }

    /**
     * 인증 여부 확인 (내부 API - user-service에서 호출)
     */
    @GetMapping("/internal/email/verified")
    public ResponseEntity<Boolean> isEmailVerified(@RequestParam String email) {
        boolean verified = emailVerificationService.isVerified(email);
        return ResponseEntity.ok(verified);
    }

    /**
     * 인증 완료 상태 삭제 (내부 API - user-service에서 회원가입 후 호출)
     */
    @DeleteMapping("/internal/email/verified")
    public ResponseEntity<Void> clearVerification(@RequestParam String email) {
        emailVerificationService.clearVerification(email);
        return ResponseEntity.ok().build();
    }

    // ========== 비밀번호 재설정 관련 API ==========

    /**
     * 비밀번호 재설정 인증코드 발송 (내부 API - user-service에서 호출)
     */
    @PostMapping("/internal/email/password-reset/send")
    public ResponseEntity<Void> sendPasswordResetCode(@RequestParam String email) {
        emailVerificationService.sendPasswordResetCode(email);
        return ResponseEntity.ok().build();
    }

    /**
     * 비밀번호 재설정 인증코드 검증 (내부 API - user-service에서 호출)
     */
    @PostMapping("/internal/email/password-reset/verify")
    public ResponseEntity<Boolean> verifyPasswordResetCode(
            @RequestParam String email,
            @RequestParam String code) {
        boolean verified = emailVerificationService.verifyPasswordResetCode(email, code);
        return ResponseEntity.ok(verified);
    }

    /**
     * 비밀번호 재설정 인증 정보 삭제 (내부 API - user-service에서 호출)
     */
    @DeleteMapping("/internal/email/password-reset/clear")
    public ResponseEntity<Void> clearPasswordResetVerification(@RequestParam String email) {
        emailVerificationService.clearPasswordResetVerification(email);
        return ResponseEntity.ok().build();
    }
}
