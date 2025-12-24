package com.pawbridge.userservice.controller;

import com.pawbridge.userservice.dto.request.AdminUserUpdateRequest;
import com.pawbridge.userservice.dto.response.DailySignupStatsResponse;
import com.pawbridge.userservice.dto.response.SignupPeriodsResponse;
import com.pawbridge.userservice.dto.response.UserInfoResponseDto;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.service.UserService;
import com.pawbridge.userservice.util.ResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 관리자 컨트롤러
 * - 회원 관리 및 통계
 * - ROLE_ADMIN만 접근 가능 (API Gateway에서 체크)
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserService userService;

    /**
     * 전체 회원 조회 (페이징)
     * - GET /api/v1/admin/users
     */
    @GetMapping("/users")
    public ResponseEntity<ResponseDTO<Page<UserInfoResponseDto>>> getAllUsers(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<UserInfoResponseDto> users = userService.getAllUsers(pageable);
        ResponseDTO<Page<UserInfoResponseDto>> response = ResponseDTO.okWithData(users);

        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 회원 검색 (관리자용)
     * - GET /api/v1/admin/users/search?keyword=검색어&role=ROLE_USER
     * - keyword: 이메일, 이름, 닉네임으로 검색 (선택)
     * - role: 역할로 필터링 (선택)
     */
    @GetMapping("/users/search")
    public ResponseEntity<ResponseDTO<Page<UserInfoResponseDto>>> searchUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Role role,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<UserInfoResponseDto> users = userService.searchUsers(keyword, role, pageable);
        ResponseDTO<Page<UserInfoResponseDto>> response = ResponseDTO.okWithData(users);

        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 특정 회원 조회
     * - GET /api/v1/admin/users/{userId}
     */
    @GetMapping("/users/{userId}")
    public ResponseEntity<ResponseDTO<UserInfoResponseDto>> getUserById(@PathVariable Long userId) {

        UserInfoResponseDto user = userService.getUserById(userId);
        ResponseDTO<UserInfoResponseDto> response = ResponseDTO.okWithData(user);

        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 회원 수정 (관리자용)
     * - PUT /api/v1/admin/users/{userId}
     */
    @PutMapping("/users/{userId}")
    public ResponseEntity<ResponseDTO<Void>> updateUser(
            @PathVariable Long userId,
            @Valid @RequestBody AdminUserUpdateRequest request) {

        userService.updateUserByAdmin(userId, request);
        ResponseDTO<Void> response = ResponseDTO.okWithMessage("회원 정보가 수정되었습니다.");

        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 회원 삭제 (관리자용)
     * - DELETE /api/v1/admin/users/{userId}
     */
    @DeleteMapping("/users/{userId}")
    public ResponseEntity<ResponseDTO<Void>> deleteUser(@PathVariable Long userId) {

        userService.deleteUserById(userId);
        ResponseDTO<Void> response = ResponseDTO.okWithMessage("회원이 삭제되었습니다.");

        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 일별 가입자 수 통계
     * - GET /api/v1/admin/stats/daily-signups?startDate=2024-01-01&endDate=2024-01-31
     */
    @GetMapping("/stats/daily-signups")
    public ResponseEntity<ResponseDTO<List<DailySignupStatsResponse>>> getDailySignupStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<DailySignupStatsResponse> stats = userService.getDailySignupStats(startDate, endDate);
        ResponseDTO<List<DailySignupStatsResponse>> response = ResponseDTO.okWithData(stats);

        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 전체 회원 수 조회
     * - GET /api/v1/admin/stats/total-users
     */
    @GetMapping("/stats/total-users")
    public ResponseEntity<ResponseDTO<Long>> getTotalUserCount() {

        Long count = userService.getTotalUserCount();
        ResponseDTO<Long> response = ResponseDTO.okWithData(count);

        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 기간별 가입자 수 통계 (관리자용)
     * - GET /api/v1/admin/stats/signup-periods
     * - 오늘, 최근 7일, 최근 30일, 이번 달의 일별 가입자 수를 한번에 반환
     */
    @GetMapping("/stats/signup-periods")
    public ResponseEntity<ResponseDTO<SignupPeriodsResponse>> getSignupPeriods() {

        SignupPeriodsResponse stats = userService.getSignupPeriods();
        ResponseDTO<SignupPeriodsResponse> response = ResponseDTO.okWithData(stats);

        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }
}
