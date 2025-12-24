package com.pawbridge.userservice.service;

import com.pawbridge.userservice.dto.request.AdminUserUpdateRequest;
import com.pawbridge.userservice.dto.request.PasswordUpdateRequestDto;
import com.pawbridge.userservice.dto.request.SignUpRequestDto;
import com.pawbridge.userservice.dto.request.UpdateNicknameRequestDto;
import com.pawbridge.userservice.dto.response.DailySignupStatsResponse;
import com.pawbridge.userservice.dto.response.SignUpResponseDto;
import com.pawbridge.userservice.dto.response.UserInfoResponseDto;
import com.pawbridge.userservice.entity.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

public interface UserService {

    // 회원가입
    SignUpResponseDto signUp(SignUpRequestDto requestDto);

    // 내 정보 조회
    UserInfoResponseDto getUserInfo(Long userId);

    // 비밀번호 수정
    void updatePassword(Long userId, PasswordUpdateRequestDto requestDto);

    // 닉네임 수정
    void updateNickname(Long userId, UpdateNicknameRequestDto requestDto);

    // 닉네임 조회 (내부 API용)
    String getUserNickname(Long userId);

    // ========== 관리자 전용 메서드 ==========

    /**
     * 전체 회원 조회 (페이징)
     */
    Page<UserInfoResponseDto> getAllUsers(Pageable pageable);

    /**
     * 회원 ID로 조회 (관리자용)
     */
    UserInfoResponseDto getUserById(Long userId);

    /**
     * 회원 수정 (관리자용)
     */
    void updateUserByAdmin(Long userId, AdminUserUpdateRequest request);

    /**
     * 회원 삭제 (관리자용)
     */
    void deleteUserById(Long userId);

    /**
     * 일별 가입자 수 통계
     */
    List<DailySignupStatsResponse> getDailySignupStats(LocalDate startDate, LocalDate endDate);

    /**
     * 전체 회원 수 조회
     */
    Long getTotalUserCount();

    /**
     * 회원 검색 (관리자용)
     * @param keyword 검색 키워드 (이메일, 이름, 닉네임)
     * @param role 역할 필터 (null이면 전체)
     * @param pageable 페이징 정보
     * @return 검색된 회원 목록
     */
    Page<UserInfoResponseDto> searchUsers(String keyword, Role role, Pageable pageable);

}
