package com.pawbridge.userservice.repository;

import com.pawbridge.userservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    /**
     * 이메일과 Provider로 사용자 조회
     * OAuth2 로그인 시 사용 (LOCAL과 GOOGLE을 구분하기 위함)
     */
    Optional<User> findByEmailAndProvider(String email, String provider);

    /**
     * Provider와 ProviderId로 사용자 조회
     * OAuth2 사용자 식별용 (향후 확장 가능성)
     */
    Optional<User> findByProviderAndProviderId(String provider, String providerId);

}
