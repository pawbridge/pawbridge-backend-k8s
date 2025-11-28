package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.dto.request.CreateCommentRequest;
import com.pawbridge.communityservice.dto.request.UpdateCommentRequest;
import com.pawbridge.communityservice.dto.response.CommentResponse;

import java.util.List;

/**
 * 댓글 서비스 인터페이스
 */
public interface CommentService {

    CommentResponse createComment(Long postId, CreateCommentRequest request, Long authorId);

    CommentResponse updateComment(Long commentId, UpdateCommentRequest request, Long authorId);

    void deleteComment(Long commentId, Long authorId);

    List<CommentResponse> getCommentsByPostId(Long postId);
}
