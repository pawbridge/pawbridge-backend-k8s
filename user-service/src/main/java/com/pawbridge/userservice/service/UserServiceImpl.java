package com.pawbridge.userservice.service;

import com.pawbridge.userservice.client.AnimalServiceClient;
import com.pawbridge.userservice.dto.response.SignupPeriodsResponse;
import com.pawbridge.userservice.email.service.EmailVerificationService;
import com.pawbridge.userservice.dto.request.AdminUserUpdateRequest;
import com.pawbridge.userservice.dto.request.PasswordUpdateRequestDto;
import com.pawbridge.userservice.dto.request.SignUpRequestDto;
import com.pawbridge.userservice.dto.request.UpdateNicknameRequestDto;
import com.pawbridge.userservice.dto.response.DailySignupStatsResponse;
import com.pawbridge.userservice.dto.response.SignUpResponseDto;
import com.pawbridge.userservice.dto.response.UserInfoResponseDto;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.exception.*;
import com.pawbridge.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final NicknameGeneratorService nicknameGeneratorService;
    private final AnimalServiceClient animalServiceClient;

    @Override
    @Transactional
    public SignUpResponseDto signUp(SignUpRequestDto requestDto) {
        // 1. 이메일 인증 확인
        boolean verified = emailVerificationService.isVerified(requestDto.email());
        if (!verified) {
            throw new EmailNotVerifiedException();
        }

        // 2. 이메일 중복 확인 (LOCAL provider)
        if (userRepository.existsByEmailAndProvider(requestDto.email(), "LOCAL")) {
            throw new EmailDuplicateException();
        }

        // 3. 비밀번호 확인
        if (!requestDto.password().equals(requestDto.rePassword())) {
            throw new InconsistentPasswordException();
        }

        // 4. Role 필수 검증
        if (requestDto.role() == null) {
            throw new RoleRequiredException();
        }

        // 5. ROLE_ADMIN은 회원가입으로 생성 불가
        if (requestDto.role() == Role.ROLE_ADMIN) {
            throw new AdminRoleNotAllowedException();
        }

        // 6. ROLE_SHELTER인 경우 careRegNo 검증
        if (requestDto.role() == Role.ROLE_SHELTER) {
            // careRegNo가 없으면 에러
            if (requestDto.careRegNo() == null || requestDto.careRegNo().isBlank()) {
                throw new ShelterCareRegNoRequiredException();
            }

            // animal-service에 보호소 존재 여부 확인
            try {
                Boolean exists = animalServiceClient.existsByCareRegNo(requestDto.careRegNo());
                if (exists == null || !exists) {
                    throw new ShelterNotFoundException();
                }
                log.info("보호소 등록번호 검증 완료: {}", requestDto.careRegNo());
            } catch (ShelterNotFoundException e) {
                throw e;
            } catch (Exception e) {
                log.error("보호소 존재 여부 확인 실패: {}", e.getMessage());
                throw new ShelterServiceUnavailableException();
            }
        }

        // 7. 닉네임 자동 생성
        String nickname = nicknameGeneratorService.generateUniqueNickname();
        log.info("자동 생성된 닉네임: {}", nickname);

        // 8. 비밀번호 암호화
        String encodedPassword = passwordEncoder.encode(requestDto.password());

        // 9. 사용자 생성
        User user = requestDto.toEntity(
                requestDto.email(),
                requestDto.name(),
                encodedPassword,
                nickname
        );

        // 10. DB 저장 (닉네임 중복 시 재시도)
        User savedUser;
        try {
            savedUser = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            log.warn("닉네임 중복 발생 (동시성), 재생성 시도");
            // 닉네임 중복으로 인한 실패 시 재시도
            String newNickname = nicknameGeneratorService.generateUniqueNickname();
            user = requestDto.toEntity(
                    requestDto.email(),
                    requestDto.name(),
                    encodedPassword,
                    newNickname
            );
            savedUser = userRepository.save(user);
            log.info("재생성된 닉네임: {}", newNickname);
        }

        // 11. 이메일 인증 정보 삭제
        try {
            emailVerificationService.clearVerification(requestDto.email());
        } catch (Exception e) {
            log.warn("이메일 인증 정보 삭제 실패 (무시): {}", e.getMessage());
        }

        log.info("회원가입 완료: email={}, nickname={}, role={}, careRegNo={}",
                savedUser.getEmail(), savedUser.getNickname(), savedUser.getRole(), savedUser.getCareRegNo());

        return SignUpResponseDto.fromEntity(savedUser);
    }

    @Override
    @Transactional(readOnly = true)
    public UserInfoResponseDto getUserInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException());

        return UserInfoResponseDto.fromEntity(user);
    }

    @Override
    @Transactional
    public void updatePassword(Long userId, PasswordUpdateRequestDto requestDto) {
        // 1. 사용자 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException());

        // 2. OAuth2 사용자는 비밀번호 변경 불가
        if (!user.isLocalUser()) {
            throw new OAuthUserCannotChangePasswordException();
        }

        // 3. 현재 비밀번호 검증
        if (!passwordEncoder.matches(requestDto.getCurrentPassword(), user.getPassword())) {
            throw new InvalidCurrentPasswordException();
        }

        // 4. 현재 비밀번호와 새 비밀번호 동일성 체크
        if (requestDto.getCurrentPassword().equals(requestDto.getNewPassword())) {
            throw new SamePasswordException();
        }

        // 5. 새 비밀번호 암호화 및 변경
        String encodedPassword = passwordEncoder.encode(requestDto.getNewPassword());
        user.updatePassword(encodedPassword);
        userRepository.save(user);

        log.info("비밀번호 변경 완료: userId={}", userId);
    }

    @Override
    @Transactional
    public void updateNickname(Long userId, UpdateNicknameRequestDto requestDto) {
        // 1. 사용자 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException());

        String newNickname = requestDto.getNickname();

        // 2. 현재 닉네임과 동일하면 변경 불필요
        if (user.getNickname().equals(newNickname)) {
            log.debug("동일한 닉네임으로 변경 시도, 변경 없음: {}", newNickname);
            return;
        }

        // 3. 닉네임 중복 체크 및 변경 (동시성 처리)
        try {
            if (userRepository.existsByNickname(newNickname)) {
                throw new NicknameDuplicateException();
            }

            user.updateNickname(newNickname);
            userRepository.save(user);

            log.info("닉네임 변경 완료: userId={}, 새 닉네임={}", userId, newNickname);
        } catch (DataIntegrityViolationException e) {
            // DB 레벨에서 UNIQUE 제약 위반 시
            log.warn("닉네임 중복 (DB 제약): {}", newNickname);
            throw new NicknameDuplicateException();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public String getUserNickname(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException());
        return user.getNickname();
    }

    // ========== 관리자 전용 메서드 ==========

    @Override
    @Transactional(readOnly = true)
    public Page<UserInfoResponseDto> getAllUsers(Pageable pageable) {
        log.info("전체 회원 조회 (관리자): page={}, size={}", pageable.getPageNumber(), pageable.getPageSize());

        Page<User> users = userRepository.findAll(pageable);
        return users.map(UserInfoResponseDto::fromEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public UserInfoResponseDto getUserById(Long userId) {
        log.info("회원 ID로 조회 (관리자): userId={}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException());
        return UserInfoResponseDto.fromEntity(user);
    }

    @Override
    @Transactional
    public void updateUserByAdmin(Long userId, AdminUserUpdateRequest request) {
        log.info("회원 수정 (관리자): userId={}, request={}", userId, request);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException());

        // 닉네임 수정
        if (request.nickname() != null && !request.nickname().isBlank()) {
            if (!user.getNickname().equals(request.nickname())) {
                if (userRepository.existsByNickname(request.nickname())) {
                    throw new NicknameDuplicateException();
                }
                user.updateNickname(request.nickname());
            }
        }

        // Role 수정
        if (request.role() != null) {
            user.updateRole(request.role());
        }

        // careRegNo 수정 (ROLE_SHELTER인 경우)
        if (request.careRegNo() != null && !request.careRegNo().isBlank()) {
            user.updateCareRegNo(request.careRegNo());
        }

        userRepository.save(user);
        log.info("회원 수정 완료 (관리자): userId={}", userId);
    }

    @Override
    @Transactional
    public void deleteUserById(Long userId) {
        log.info("회원 삭제 (관리자): userId={}", userId);

        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException();
        }

        userRepository.deleteById(userId);
        log.info("회원 삭제 완료 (관리자): userId={}", userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailySignupStatsResponse> getDailySignupStats(LocalDate startDate, LocalDate endDate) {
        log.info("일별 가입자 수 통계 조회: startDate={}, endDate={}", startDate, endDate);

        List<DailySignupStatsResponse> stats = userRepository.countDailySignups(startDate, endDate);
        log.info("일별 가입자 수 통계 조회 완료: {} 건", stats.size());

        return stats;
    }

    @Override
    @Transactional(readOnly = true)
    public Long getTotalUserCount() {
        log.info("전체 회원 수 조회");

        Long count = userRepository.count();
        log.info("전체 회원 수: {}", count);

        return count;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserInfoResponseDto> searchUsers(String keyword, Role role, Pageable pageable) {
        log.info("회원 검색 (관리자): keyword={}, role={}, page={}, size={}",
                keyword, role, pageable.getPageNumber(), pageable.getPageSize());

        Page<User> users = userRepository.searchUsers(keyword, role, pageable);
        Page<UserInfoResponseDto> result = users.map(UserInfoResponseDto::fromEntity);

        log.info("회원 검색 완료: {} 건", result.getTotalElements());

        return result;
    }

    /**
     * 빈 날짜 채우기 (count = 0)
     * - DB에서 가져온 데이터에 누락된 날짜를 채워서 반환
     * @param startDate 시작 날짜
     * @param endDate 종료 날짜
     * @param dbResults DB 조회 결과
     * @return 빈 날짜가 채워진 결과 (날짜 순서 정렬)
     */
    private List<DailySignupStatsResponse> fillMissingDates(
            LocalDate startDate, LocalDate endDate, List<DailySignupStatsResponse> dbResults) {

        // 1. DB 결과를 Map으로 변환 (날짜 -> 카운트)
        Map<LocalDate, Long> dateCountMap = dbResults.stream()
                .collect(Collectors.toMap(
                        DailySignupStatsResponse::date,
                        DailySignupStatsResponse::count
                ));

        // 2. 시작 날짜부터 종료 날짜까지 모든 날짜 생성 (빈 날짜는 count = 0)
        List<DailySignupStatsResponse> result = new ArrayList<>();
        LocalDate currentDate = startDate;

        while (!currentDate.isAfter(endDate)) {
            Long count = dateCountMap.getOrDefault(currentDate, 0L);
            result.add(new DailySignupStatsResponse(currentDate, count));
            currentDate = currentDate.plusDays(1);
        }

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public SignupPeriodsResponse getSignupPeriods() {
        log.info("기간별 가입자 수 통계 조회");

        LocalDate now = LocalDate.now();

        // 1. 오늘 (오늘 하루)
        LocalDate todayStart = now;
        LocalDate todayEnd = now;

        // 2. 최근 7일 (오늘 포함 7일)
        LocalDate last7DaysStart = now.minusDays(6);

        // 3. 최근 30일 (오늘 포함 30일)
        LocalDate last30DaysStart = now.minusDays(29);

        // 4. 이번 달 (1일 ~ 오늘)
        LocalDate thisMonthStart = now.withDayOfMonth(1);

        // 4번의 쿼리 실행 (각각 인덱스 활용)
        List<DailySignupStatsResponse> todayRaw = userRepository.countDailySignups(todayStart, todayEnd);
        List<DailySignupStatsResponse> last7DaysRaw = userRepository.countDailySignups(last7DaysStart, now);
        List<DailySignupStatsResponse> last30DaysRaw = userRepository.countDailySignups(last30DaysStart, now);
        List<DailySignupStatsResponse> thisMonthRaw = userRepository.countDailySignups(thisMonthStart, now);

        // 빈 날짜 채우기 (count = 0)
        List<DailySignupStatsResponse> today = fillMissingDates(todayStart, todayEnd, todayRaw);
        List<DailySignupStatsResponse> last7Days = fillMissingDates(last7DaysStart, now, last7DaysRaw);
        List<DailySignupStatsResponse> last30Days = fillMissingDates(last30DaysStart, now, last30DaysRaw);
        List<DailySignupStatsResponse> thisMonth = fillMissingDates(thisMonthStart, now, thisMonthRaw);

        SignupPeriodsResponse response = SignupPeriodsResponse.builder()
                .today(today)
                .last7Days(last7Days)
                .last30Days(last30Days)
                .thisMonth(thisMonth)
                .build();

        log.info("기간별 가입자 수 통계 조회 완료: 오늘={}, 최근7일={}, 최근30일={}, 이번달={}",
                today.size(), last7Days.size(), last30Days.size(), thisMonth.size());

        return response;
    }
}
