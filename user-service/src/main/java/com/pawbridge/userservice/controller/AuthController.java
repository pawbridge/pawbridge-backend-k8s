package com.pawbridge.userservice.controller;

import com.pawbridge.userservice.dto.request.LogoutRequestDto;
import com.pawbridge.userservice.dto.request.RefreshTokenRequestDto;
import com.pawbridge.userservice.dto.respone.RefreshTokenResponseDto;
import com.pawbridge.userservice.service.AuthService;
import com.pawbridge.userservice.util.ResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    /**
     * 토큰 재발급
     */
    @PostMapping("/refresh")
    public ResponseEntity<ResponseDTO<RefreshTokenResponseDto>> refreshToken(
            @Valid @RequestBody RefreshTokenRequestDto requestDto) {

        RefreshTokenResponseDto responseDto = authService.refreshToken(requestDto);
        ResponseDTO<RefreshTokenResponseDto> response = ResponseDTO.okWithData(
                responseDto, "토큰이 재발급되었습니다.");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 로그아웃
     */
    @PostMapping("/logout")
    public ResponseEntity<ResponseDTO<Void>> logout(
            @Valid @RequestBody LogoutRequestDto requestDto) {

        authService.logout(requestDto.userId());
        ResponseDTO<Void> response = ResponseDTO.ok("로그아웃되었습니다.");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }
}
