package com.pawbridge.userservice.controller;

import com.pawbridge.userservice.dto.request.SignUpRequestDto;
import com.pawbridge.userservice.dto.respone.SignUpResponseDto;
import com.pawbridge.userservice.dto.respone.UserInfoResponseDto;
import com.pawbridge.userservice.service.UserService;
import com.pawbridge.userservice.util.ResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/user")
public class UserController {

    private final UserService userService;

    // 회원가입
    @PostMapping("/signup")
    public ResponseEntity<ResponseDTO<SignUpResponseDto>> signup(
            @Valid @RequestBody SignUpRequestDto signUpRequestDto) {

        SignUpResponseDto signUpResponseDto = userService.signUp(signUpRequestDto);
        ResponseDTO<SignUpResponseDto> response = ResponseDTO.okWithData(signUpResponseDto);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    // 내 정보 조회
    @GetMapping("/me")
    public ResponseEntity<ResponseDTO<UserInfoResponseDto>> getUserInfo(
            @RequestHeader("X-User-Id") Long userId) {

        UserInfoResponseDto userInfoResponseDto = userService.getUserInfo(userId);
        ResponseDTO<UserInfoResponseDto> response = ResponseDTO.okWithData(userInfoResponseDto);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

}
