package com.pawbridge.userservice.service;

import com.pawbridge.userservice.email.service.EmailVerificationService;
import com.pawbridge.userservice.dto.request.PasswordUpdateRequestDto;
import com.pawbridge.userservice.dto.request.SignUpRequestDto;
import com.pawbridge.userservice.dto.request.UpdateNicknameRequestDto;
import com.pawbridge.userservice.dto.respone.SignUpResponseDto;
import com.pawbridge.userservice.dto.respone.UserInfoResponseDto;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.exception.*;
import com.pawbridge.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final NicknameGeneratorService nicknameGeneratorService;

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

        // 4. 닉네임 자동 생성
        String nickname = nicknameGeneratorService.generateUniqueNickname();
        log.info("자동 생성된 닉네임: {}", nickname);

        // 5. 비밀번호 암호화
        String encodedPassword = passwordEncoder.encode(requestDto.password());

        // 6. 사용자 생성
        User user = requestDto.toEntity(
                requestDto.email(),
                requestDto.name(),
                encodedPassword,
                nickname
        );

        // 7. DB 저장 (닉네임 중복 시 재시도)
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

        // 8. 이메일 인증 정보 삭제
        try {
            emailVerificationService.clearVerification(requestDto.email());
        } catch (Exception e) {
            log.warn("이메일 인증 정보 삭제 실패 (무시): {}", e.getMessage());
        }

        log.info("회원가입 완료: {}, 닉네임: {}", savedUser.getEmail(), savedUser.getNickname());

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
}
