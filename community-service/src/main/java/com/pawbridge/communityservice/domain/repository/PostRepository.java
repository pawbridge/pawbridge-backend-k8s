package com.pawbridge.communityservice.domain.repository;

import com.pawbridge.communityservice.domain.entity.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Post Repository
 */
public interface PostRepository extends JpaRepository<Post, Long> {

    // Soft delete되지 않은 게시글만 조회 (최신순)
    List<Post> findByDeletedAtIsNullOrderByCreatedAtDesc();

    // Soft delete되지 않은 게시글만 조회 (페이징)
    Page<Post> findByDeletedAtIsNull(Pageable pageable);

    // postId로 조회 (삭제되지 않은 것만)
    Optional<Post> findByPostIdAndDeletedAtIsNull(Long postId);

    /**
     * 특정 기간에 작성된 게시글 수 조회 (관리자용)
     * @param startDateTime 시작 시간
     * @param endDateTime 종료 시간
     * @return 게시글 수
     */
    @Query("SELECT COUNT(p) FROM Post p WHERE p.createdAt >= :startDateTime AND p.createdAt < :endDateTime AND p.deletedAt IS NULL")
    Long countByCreatedAtBetween(@Param("startDateTime") LocalDateTime startDateTime, @Param("endDateTime") LocalDateTime endDateTime);
}
