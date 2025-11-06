package com.pawbridge.userservice.service;

import com.pawbridge.userservice.dto.request.SignUpRequestDto;
import com.pawbridge.userservice.dto.respone.SignUpResponseDto;

public interface UserService {

    // 회원가입
    SignUpResponseDto signUp(SignUpRequestDto requestDto);

}
