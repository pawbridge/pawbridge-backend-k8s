package com.pawbridge.userservice.service;

import com.pawbridge.userservice.dto.request.SignUpRequestDto;
import com.pawbridge.userservice.dto.respone.SignUpResponseDto;
import com.pawbridge.userservice.dto.respone.UserInfoResponseDto;

public interface UserService {

    // 회원가입
    SignUpResponseDto signUp(SignUpRequestDto requestDto);

    // 내 정보 조회
    UserInfoResponseDto getUserInfo(Long userId);

}
