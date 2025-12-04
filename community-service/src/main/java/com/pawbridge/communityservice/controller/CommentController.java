package com.pawbridge.communityservice.controller;

import com.pawbridge.communityservice.dto.request.CreateCommentRequest;
import com.pawbridge.communityservice.dto.request.UpdateCommentRequest;
import com.pawbridge.communityservice.dto.response.CommentResponse;
import com.pawbridge.communityservice.service.CommentService;
import com.pawbridge.communityservice.util.ResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 댓글 Controller
 */
@RestController
@RequestMapping("/api/v1/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    /**
     * 댓글 생성
     */
    @PostMapping("/posts/{postId}")
    public ResponseEntity<ResponseDTO<CommentResponse>> createComment(
            @PathVariable Long postId,
            @Valid @RequestBody CreateCommentRequest request,
            @RequestHeader("X-User-Id") Long userId) {

        CommentResponse commentResponse = commentService.createComment(postId, request, userId);
        ResponseDTO<CommentResponse> response = ResponseDTO.okWithData(commentResponse, "댓글이 생성되었습니다.");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 댓글 수정
     */
    @PutMapping("/{commentId}")
    public ResponseEntity<ResponseDTO<CommentResponse>> updateComment(
            @PathVariable Long commentId,
            @Valid @RequestBody UpdateCommentRequest request,
            @RequestHeader("X-User-Id") Long userId) {

        CommentResponse commentResponse = commentService.updateComment(commentId, request, userId);
        ResponseDTO<CommentResponse> response = ResponseDTO.okWithData(commentResponse, "댓글이 수정되었습니다.");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 댓글 삭제
     */
    @DeleteMapping("/{commentId}")
    public ResponseEntity<ResponseDTO<Void>> deleteComment(
            @PathVariable Long commentId,
            @RequestHeader("X-User-Id") Long userId) {

        commentService.deleteComment(commentId, userId);
        ResponseDTO<Void> response = ResponseDTO.okWithMessage("댓글이 삭제되었습니다.");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 특정 게시글의 댓글 목록 조회
     */
    @GetMapping("/posts/read/{postId}")
    public ResponseEntity<ResponseDTO<List<CommentResponse>>> getCommentsByPostId(@PathVariable Long postId) {
        List<CommentResponse> commentResponses = commentService.getCommentsByPostId(postId);
        ResponseDTO<List<CommentResponse>> response = ResponseDTO.okWithData(commentResponses);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }
}
