package com.pawbridge.userservice.service;

import com.pawbridge.userservice.dto.request.PasswordUpdateRequestDto;
import com.pawbridge.userservice.dto.request.SignUpRequestDto;
import com.pawbridge.userservice.dto.request.UpdateNicknameRequestDto;
import com.pawbridge.userservice.dto.response.SignUpResponseDto;
import com.pawbridge.userservice.dto.response.UserInfoResponseDto;

public interface UserService {

    // 회원가입
    SignUpResponseDto signUp(SignUpRequestDto requestDto);

    // 내 정보 조회
    UserInfoResponseDto getUserInfo(Long userId);

    // 비밀번호 수정
    void updatePassword(Long userId, PasswordUpdateRequestDto requestDto);

    // 닉네임 수정
    void updateNickname(Long userId, UpdateNicknameRequestDto requestDto);

}
