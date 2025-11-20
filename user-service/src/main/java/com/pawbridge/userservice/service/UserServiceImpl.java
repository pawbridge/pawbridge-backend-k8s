package com.pawbridge.userservice.service;

import com.pawbridge.userservice.client.EmailServiceClient;
import com.pawbridge.userservice.dto.request.SignUpRequestDto;
import com.pawbridge.userservice.dto.respone.SignUpResponseDto;
import com.pawbridge.userservice.dto.respone.UserInfoResponseDto;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.exception.EmailDuplicateException;
import com.pawbridge.userservice.exception.EmailNotVerifiedException;
import com.pawbridge.userservice.exception.InconsistentPasswordException;
import com.pawbridge.userservice.exception.UserNotFoundException;
import com.pawbridge.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder encoder;
    private final EmailServiceClient emailServiceClient;

    @Override
    @Transactional
    // 회원가입
    public SignUpResponseDto signUp(SignUpRequestDto signUpRequestDto) {

        // 1. 이메일 인증 여부 확인
        Boolean verified = emailServiceClient.isEmailVerified(signUpRequestDto.email());
        if (verified == null || !verified) {
            throw new EmailNotVerifiedException();
        }

        // 2. 이메일 중복 여부 검증 (LOCAL provider만 확인)
        Optional<User> existedUser = userRepository.findByEmailAndProvider(
                signUpRequestDto.email(), "LOCAL");
        if (existedUser.isPresent()) {
            throw new EmailDuplicateException();
        }

        // 3. 비밀번호와 비밀번호 확인 일치 여부 검증
        if (!signUpRequestDto.rePassword().equals(signUpRequestDto.password())) {
            throw new InconsistentPasswordException();
        }

        // 4. 비밀번호 암호화
        String encodingPassword = encoder.encode(signUpRequestDto.password());

        User newUser = signUpRequestDto.toEntity(
                signUpRequestDto.email(),
                signUpRequestDto.name(),
                encodingPassword);

        userRepository.save(newUser);

        // 5. 인증 완료 상태 삭제 (재사용 방지)
        emailServiceClient.clearVerification(signUpRequestDto.email());

        return SignUpResponseDto.fromEntity(newUser);
    }

    @Override
    @Transactional(readOnly = true)
    // 내 정보 조회
    public UserInfoResponseDto getUserInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(UserNotFoundException::new);

        return UserInfoResponseDto.fromEntity(user);
    }

}
