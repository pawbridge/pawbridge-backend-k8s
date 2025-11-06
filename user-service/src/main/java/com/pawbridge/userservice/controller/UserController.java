package com.pawbridge.userservice.controller;

import com.pawbridge.userservice.dto.request.SignUpRequestDto;
import com.pawbridge.userservice.dto.respone.SignUpResponseDto;
import com.pawbridge.userservice.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    // 회원가입
    @PostMapping("/sign-up")
    public ResponseEntity<SignUpResponseDto> signup(
            @Valid @RequestBody SignUpRequestDto signUpRequestDto) {

        SignUpResponseDto signUpResponseDto = userService.signUp(signUpRequestDto);
        return ResponseEntity.ok(signUpResponseDto);

    }

}
