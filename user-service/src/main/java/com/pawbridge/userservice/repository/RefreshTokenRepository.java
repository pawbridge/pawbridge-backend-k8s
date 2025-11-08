package com.pawbridge.userservice.repository;

import com.pawbridge.userservice.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * 토큰 값으로 RefreshToken 조회
     */
    Optional<RefreshToken> findByToken(String token);

    /**
     * 사용자 ID로 RefreshToken 조회
     */
    Optional<RefreshToken> findByUserId(Long userId);

    /**
     * 사용자 ID로 RefreshToken 삭제
     */
    void deleteByUserId(Long userId);

    /**
     * 토큰 값으로 RefreshToken 존재 여부 확인
     */
    boolean existsByToken(String token);
}
