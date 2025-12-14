package com.pawbridge.userservice.controller;

import com.pawbridge.userservice.dto.request.PasswordUpdateRequestDto;
import com.pawbridge.userservice.dto.request.SignUpRequestDto;
import com.pawbridge.userservice.dto.request.UpdateNicknameRequestDto;
import com.pawbridge.userservice.dto.response.SignUpResponseDto;
import com.pawbridge.userservice.dto.response.UserInfoResponseDto;
import com.pawbridge.userservice.service.UserService;
import com.pawbridge.userservice.util.ResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    /**
     * 회원가입
     */
    @PostMapping("/signup")
    public ResponseEntity<ResponseDTO<SignUpResponseDto>> signup(
            @Valid @RequestBody SignUpRequestDto signUpRequestDto) {

        SignUpResponseDto signUpResponseDto = userService.signUp(signUpRequestDto);
        ResponseDTO<SignUpResponseDto> response = ResponseDTO.okWithData(signUpResponseDto);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 내 정보 조회
     */
    @GetMapping("/me")
    public ResponseEntity<ResponseDTO<UserInfoResponseDto>> getUserInfo(
            @RequestHeader(value = "X-User-Id", required = true) Long userId) {

        UserInfoResponseDto userInfoResponseDto = userService.getUserInfo(userId);
        ResponseDTO<UserInfoResponseDto> response = ResponseDTO.okWithData(userInfoResponseDto);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 비밀번호 수정 (로그인 상태)
     */
    @PutMapping("/me/password")
    public ResponseEntity<ResponseDTO<Void>> updatePassword(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @Valid @RequestBody PasswordUpdateRequestDto requestDto) {

        userService.updatePassword(userId, requestDto);
        ResponseDTO<Void> response = ResponseDTO.okWithMessage("비밀번호가 변경되었습니다.");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 닉네임 수정
     */
    @PutMapping("/me/nickname")
    public ResponseEntity<ResponseDTO<Void>> updateNickname(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @Valid @RequestBody UpdateNicknameRequestDto requestDto) {

        userService.updateNickname(userId, requestDto);
        ResponseDTO<Void> response = ResponseDTO.okWithMessage("닉네임이 변경되었습니다.");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 닉네임 조회 (내부 API - 마이크로서비스 간 호출용)
     */
    @GetMapping("/internal/{userId}/nickname")
    public ResponseEntity<String> getUserNickname(@PathVariable Long userId) {
        String nickname = userService.getUserNickname(userId);
        return ResponseEntity.ok(nickname);
    }

}
