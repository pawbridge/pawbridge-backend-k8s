package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.domain.entity.Comment;
import com.pawbridge.communityservice.domain.repository.CommentRepository;
import com.pawbridge.communityservice.domain.repository.PostRepository;
import com.pawbridge.communityservice.dto.request.CreateCommentRequest;
import com.pawbridge.communityservice.dto.request.UpdateCommentRequest;
import com.pawbridge.communityservice.dto.response.CommentResponse;
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

    /**
     * 댓글 생성
     */
    @Override
    @Transactional
    public CommentResponse createComment(Long postId, CreateCommentRequest request, Long authorId) {
        // 게시글 존재 확인
        postRepository.findByPostIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new IllegalArgumentException("게시글을 찾을 수 없습니다"));

        Comment comment = Comment.builder()
                .postId(postId)
                .authorId(authorId)
                .content(request.content())
                .build();

        Comment saved = commentRepository.save(comment);
        log.info("✅ Comment created: commentId={}", saved.getCommentId());

        return CommentResponse.fromEntity(saved);
    }

    /**
     * 댓글 수정
     */
    @Override
    @Transactional
    public CommentResponse updateComment(Long commentId, UpdateCommentRequest request, Long authorId) {
        Comment comment = commentRepository.findByCommentIdAndDeletedAtIsNull(commentId)
                .orElseThrow(() -> new IllegalArgumentException("댓글을 찾을 수 없습니다"));

        // 권한 체크
        if (!comment.getAuthorId().equals(authorId)) {
            throw new IllegalArgumentException("수정 권한이 없습니다");
        }

        comment.update(request.content());
        Comment updated = commentRepository.save(comment);

        log.info("✅ Comment updated: commentId={}", commentId);
        return CommentResponse.fromEntity(updated);
    }

    /**
     * 댓글 삭제 (Soft Delete)
     */
    @Override
    @Transactional
    public void deleteComment(Long commentId, Long authorId) {
        Comment comment = commentRepository.findByCommentIdAndDeletedAtIsNull(commentId)
                .orElseThrow(() -> new IllegalArgumentException("댓글을 찾을 수 없습니다"));

        // 권한 체크
        if (!comment.getAuthorId().equals(authorId)) {
            throw new IllegalArgumentException("삭제 권한이 없습니다");
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
                .map(CommentResponse::fromEntity)
                .collect(Collectors.toList());
    }
}
