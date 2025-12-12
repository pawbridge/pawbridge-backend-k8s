package com.pawbridge.userservice.email.controller;

import com.pawbridge.userservice.email.dto.request.SendVerificationCodeRequest;
import com.pawbridge.userservice.email.dto.request.VerifyCodeRequest;
import com.pawbridge.userservice.email.dto.response.EmailVerifiedResponse;
import com.pawbridge.userservice.email.service.EmailVerificationService;
import com.pawbridge.userservice.util.ResponseDTO;
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
    @PostMapping("/api/v1/email/send")
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
    @PostMapping("/api/v1/email/verify")
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
}
