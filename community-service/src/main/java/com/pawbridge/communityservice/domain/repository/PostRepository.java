package com.pawbridge.communityservice.domain.repository;

import com.pawbridge.communityservice.domain.entity.Post;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Post Repository
 */
public interface PostRepository extends JpaRepository<Post, Long> {

    // Soft delete되지 않은 게시글만 조회
    List<Post> findByDeletedAtIsNull();

    // postId로 조회 (삭제되지 않은 것만)
    Optional<Post> findByPostIdAndDeletedAtIsNull(Long postId);
}
