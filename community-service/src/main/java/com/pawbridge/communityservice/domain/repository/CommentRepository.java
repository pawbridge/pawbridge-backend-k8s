package com.pawbridge.communityservice.domain.repository;

import com.pawbridge.communityservice.domain.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Comment Repository
 */
public interface CommentRepository extends JpaRepository<Comment, Long> {

    // 특정 게시글의 댓글 목록 조회 (삭제되지 않은 것만, 최신순)
    List<Comment> findByPostIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long postId);

    // commentId로 조회 (삭제되지 않은 것만)
    Optional<Comment> findByCommentIdAndDeletedAtIsNull(Long commentId);
}
