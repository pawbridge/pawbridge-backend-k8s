package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.domain.entity.Comment;
import com.pawbridge.communityservice.domain.repository.CommentRepository;
import com.pawbridge.communityservice.domain.repository.PostRepository;
import com.pawbridge.communityservice.dto.request.CreateCommentRequest;
import com.pawbridge.communityservice.dto.request.UpdateCommentRequest;
import com.pawbridge.communityservice.dto.response.CommentResponse;
import com.pawbridge.communityservice.exception.CommentNotFoundException;
import com.pawbridge.communityservice.exception.PostNotFoundException;
import com.pawbridge.communityservice.exception.UnauthorizedCommentAccessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 댓글 서비스 구현체
 *
 * 참고: 댓글은 Kafka 이벤트 발행 없음 (동기 처리)
 * - Elasticsearch 인덱싱 불필요
 * - 같은 서비스 내 처리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommentServiceImpl implements CommentService {

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final UserServiceClient userServiceClient;

    /**
     * 댓글 생성
     */
    @Override
    @Transactional
    public CommentResponse createComment(Long postId, CreateCommentRequest request, Long authorId) {
        // 게시글 존재 확인
        postRepository.findByPostIdAndDeletedAtIsNull(postId)
                .orElseThrow(PostNotFoundException::new);

        Comment comment = Comment.builder()
                .postId(postId)
                .authorId(authorId)
                .content(request.content())
                .build();

        Comment saved = commentRepository.save(comment);
        log.info("✅ Comment created: commentId={}", saved.getCommentId());

        String authorNickname = getUserNickname(authorId);
        return CommentResponse.fromEntity(saved, authorNickname);
    }

    /**
     * 댓글 수정
     */
    @Override
    @Transactional
    public CommentResponse updateComment(Long commentId, UpdateCommentRequest request, Long authorId) {
        Comment comment = commentRepository.findByCommentIdAndDeletedAtIsNull(commentId)
                .orElseThrow(CommentNotFoundException::new);

        // 권한 체크
        if (!comment.getAuthorId().equals(authorId)) {
            throw new UnauthorizedCommentAccessException();
        }

        comment.update(request.content());
        Comment updated = commentRepository.save(comment);

        log.info("✅ Comment updated: commentId={}", commentId);

        String authorNickname = getUserNickname(authorId);
        return CommentResponse.fromEntity(updated, authorNickname);
    }

    /**
     * 댓글 삭제 (Soft Delete)
     */
    @Override
    @Transactional
    public void deleteComment(Long commentId, Long authorId) {
        Comment comment = commentRepository.findByCommentIdAndDeletedAtIsNull(commentId)
                .orElseThrow(CommentNotFoundException::new);

        // 권한 체크
        if (!comment.getAuthorId().equals(authorId)) {
            throw new UnauthorizedCommentAccessException();
        }

        comment.delete();
        commentRepository.save(comment);

        log.info("✅ Comment deleted: commentId={}", commentId);
    }

    /**
     * 특정 게시글의 댓글 목록 조회
     */
    @Override
    @Transactional(readOnly = true)
    public List<CommentResponse> getCommentsByPostId(Long postId) {
        return commentRepository.findByPostIdAndDeletedAtIsNullOrderByCreatedAtAsc(postId).stream()
                .map(comment -> {
                    String authorNickname = getUserNickname(comment.getAuthorId());
                    return CommentResponse.fromEntity(comment, authorNickname);
                })
                .collect(Collectors.toList());
    }

    /**
     * 사용자 닉네임 조회 헬퍼 메서드
     * User Service가 다운되거나 사용자를 찾을 수 없을 경우 기본값 반환
     */
    private String getUserNickname(Long userId) {
        try {
            return userServiceClient.getUserNickname(userId);
        } catch (Exception e) {
            log.warn("Failed to fetch nickname for userId={}, using default. Error: {}", userId, e.getMessage());
            return "사용자" + userId;
        }
    }
}
