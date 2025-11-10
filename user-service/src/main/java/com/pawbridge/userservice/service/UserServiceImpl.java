package com.pawbridge.userservice.service;

import com.pawbridge.userservice.dto.request.SignUpRequestDto;
import com.pawbridge.userservice.dto.respone.SignUpResponseDto;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.exception.EmailDuplicateException;
import com.pawbridge.userservice.exception.InconsistentPasswordException;
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

    @Override
    @Transactional
    // 회원가입
    public SignUpResponseDto signUp(SignUpRequestDto signUpRequestDto) {

        // 이메일 중복 여부 검증
        Optional<User> existedUser = userRepository.findByEmail(signUpRequestDto.email());
        if (existedUser.isPresent()) {
            throw new EmailDuplicateException();
        }

        // 비밀번호와 비밀번호 확인 일치 여부 검증
        if (!signUpRequestDto.rePassword().equals(signUpRequestDto.password())) {
            throw new InconsistentPasswordException();
        }

        // 비밀번호 암호화
        String encodingPassword = encoder.encode(signUpRequestDto.password());

        User newUser = signUpRequestDto.toEntity(
                signUpRequestDto.email(),
                signUpRequestDto.name(),
                encodingPassword);

        userRepository.save(newUser);

        return SignUpResponseDto.fromEntity(newUser);
    }

}
