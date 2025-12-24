package com.pawbridge.userservice.repository;

import com.pawbridge.userservice.entity.Favorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    /**
     * 사용자별 찜 목록 조회 (최신순)
     */
    List<Favorite> findAllByUserUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 특정 찜 조회
     */
    Optional<Favorite> findByUserUserIdAndAnimalId(Long userId, Long animalId);

    /**
     * 찜 존재 여부 확인
     */
    boolean existsByUserUserIdAndAnimalId(Long userId, Long animalId);

    /**
     * 찜 삭제 (반환값: 삭제된 행 개수)
     */
    @Modifying
    @Query("DELETE FROM Favorite f WHERE f.user.userId = :userId AND f.animalId = :animalId")
    int deleteByUserUserIdAndAnimalId(@Param("userId") Long userId, @Param("animalId") Long animalId);

    /**
     * 사용자의 모든 찜 삭제 (회원 탈퇴 시)
     */
    @Modifying
    @Query("DELETE FROM Favorite f WHERE f.user.userId = :userId")
    void deleteAllByUserUserId(@Param("userId") Long userId);
}
