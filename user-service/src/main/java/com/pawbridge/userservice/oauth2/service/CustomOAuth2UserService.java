package com.pawbridge.userservice.oauth2.service;

import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.oauth2.dto.GoogleOAuth2UserInfo;
import com.pawbridge.userservice.oauth2.dto.OAuth2UserInfo;
import com.pawbridge.userservice.oauth2.exception.OAuth2ProcessingException;
import com.pawbridge.userservice.repository.UserRepository;
import com.pawbridge.userservice.security.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OAuth2 로그인 시 사용자 정보를 처리하는 서비스
 * Google OAuth2 인증 후 사용자 정보를 조회하거나 신규 등록
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    /**
     * OAuth2 인증 후 사용자 정보 로드
     *
     * @param userRequest OAuth2 사용자 요청 (인증 정보 포함)
     * @return OAuth2User 구현체 (PrincipalDetails)
     * @throws OAuth2AuthenticationException OAuth2 처리 실패 시
     */
    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        try {
            // 1. Google에서 사용자 정보 가져오기
            OAuth2User oAuth2User = super.loadUser(userRequest);

            // 2. Provider 검증 (현재는 Google만 지원)
            String registrationId = userRequest.getClientRegistration().getRegistrationId();
            if (!"google".equalsIgnoreCase(registrationId)) {
                log.warn("지원하지 않는 OAuth2 제공자: {}", registrationId);
                throw new OAuth2ProcessingException("지원하지 않는 로그인 방식입니다: " + registrationId);
            }

            // 3. OAuth2UserInfo 생성 (Google 사용자 정보 추상화)
            OAuth2UserInfo userInfo = new GoogleOAuth2UserInfo(oAuth2User.getAttributes());

            // 4. 이메일 검증
            String email = userInfo.getEmail();
            if (email == null || email.isEmpty()) {
                log.warn("OAuth2 사용자 이메일 정보 없음");
                throw new OAuth2ProcessingException("이메일 정보를 가져올 수 없습니다");
            }

            String name = userInfo.getName();
            String providerId = userInfo.getProviderId();

            log.info("OAuth2 로그인 시도: email={}, provider=GOOGLE", email);

            // 5. 사용자 조회 또는 생성
            User user = userRepository.findByEmailAndProvider(email, "GOOGLE")
                    .orElseGet(() -> {
                        // LOCAL 계정 충돌 확인
                        if (userRepository.findByEmailAndProvider(email, "LOCAL").isPresent()) {
                            log.warn("이메일 충돌: {} (LOCAL 계정이 이미 존재)", email);
                            throw new OAuth2ProcessingException(
                                    "이미 해당 이메일로 가입된 계정이 있습니다. 일반 로그인을 이용해주세요.");
                        }

                        // 새 Google 사용자 생성
                        log.info("새 Google 사용자 생성: email={}", email);
                        User newUser = User.createSocialUser(email, name, "GOOGLE", providerId);
                        return userRepository.save(newUser);
                    });

            log.info("OAuth2 사용자 로드 완료: userId={}, email={}", user.getUserId(), user.getEmail());

            // 6. PrincipalDetails 반환 (UserDetails + OAuth2User)
            return new PrincipalDetails(user, oAuth2User.getAttributes());

        } catch (OAuth2AuthenticationException e) {
            // OAuth2 관련 예외는 그대로 전달
            log.error("OAuth2 인증 실패: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            // 기타 예외는 OAuth2ProcessingException으로 래핑
            log.error("OAuth2 처리 중 예상치 못한 오류: {}", e.getMessage(), e);
            throw new OAuth2ProcessingException("로그인 처리 중 오류가 발생했습니다");
        }
    }
}
