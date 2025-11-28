package com.pawbridge.communityservice.controller;

import com.pawbridge.communityservice.dto.request.CreatePostRequest;
import com.pawbridge.communityservice.dto.request.UpdatePostRequest;
import com.pawbridge.communityservice.dto.response.PostResponse;
import com.pawbridge.communityservice.service.PostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 게시글 Controller
 *
 * 인증: API Gateway의 JWT 필터에서 처리
 * - X-User-Id 헤더로 사용자 ID 전달받음
 */
@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    /**
     * 게시글 생성
     * - multipart/form-data로 JSON + 이미지 파일 받음
     * - request: JSON 형식의 게시글 정보
     * - images: 이미지 파일 배열 (선택)
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PostResponse> createPost(
            @Valid @RequestPart("request") CreatePostRequest request,
            @RequestPart(value = "images", required = false) MultipartFile[] images,
            @RequestHeader("X-User-Id") Long userId) {

        PostResponse response = postService.createPost(request, images, userId);
        return ResponseEntity.ok(response);
    }

    /**
     * 게시글 수정
     * - multipart/form-data로 JSON + 이미지 파일 받음
     * - request: JSON 형식의 게시글 정보
     * - images: 새로 업로드할 이미지 파일 배열 (선택, 기존 이미지는 모두 삭제됨)
     */
    @PutMapping(value = "/{postId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PostResponse> updatePost(
            @PathVariable Long postId,
            @Valid @RequestPart("request") UpdatePostRequest request,
            @RequestPart(value = "images", required = false) MultipartFile[] images,
            @RequestHeader("X-User-Id") Long userId) {

        PostResponse response = postService.updatePost(postId, request, images, userId);
        return ResponseEntity.ok(response);
    }

    /**
     * 게시글 삭제
     */
    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> deletePost(
            @PathVariable Long postId,
            @RequestHeader("X-User-Id") Long userId) {

        postService.deletePost(postId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 게시글 단건 조회
     */
    @GetMapping("read/{postId}")
    public ResponseEntity<PostResponse> getPost(@PathVariable Long postId) {
        PostResponse response = postService.getPost(postId);
        return ResponseEntity.ok(response);
    }

    /**
     * 게시글 목록 조회
     */
    @GetMapping("read")
    public ResponseEntity<List<PostResponse>> getAllPosts() {
        List<PostResponse> response = postService.getAllPosts();
        return ResponseEntity.ok(response);
    }
}
