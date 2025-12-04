package com.pawbridge.communityservice.controller;

import com.pawbridge.communityservice.domain.entity.BoardType;
import com.pawbridge.communityservice.dto.request.CreatePostRequest;
import com.pawbridge.communityservice.dto.request.UpdatePostRequest;
import com.pawbridge.communityservice.dto.response.PostResponse;
import com.pawbridge.communityservice.service.PostService;
import com.pawbridge.communityservice.util.ResponseDTO;
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
     * - multipart/form-data로 개별 필드 + 미디어 파일 받음
     * - title: 게시글 제목
     * - content: 게시글 내용
     * - boardType: 게시판 타입 (MISSING, PROTECTION, REPORT, ADOPTION)
     * - files: 미디어 파일 배열 (이미지 + 영상, 선택)
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResponseDTO<PostResponse>> createPost(
            @RequestParam("title") String title,
            @RequestParam("content") String content,
            @RequestParam("boardType") BoardType boardType,
            @RequestPart(value = "files", required = false) MultipartFile[] files,
            @RequestHeader("X-User-Id") Long userId) {

        CreatePostRequest request = new CreatePostRequest(title, content, boardType);
        PostResponse postResponse = postService.createPost(request, files, userId);
        ResponseDTO<PostResponse> response = ResponseDTO.okWithData(postResponse, "게시글이 생성되었습니다.");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 게시글 수정
     * - multipart/form-data로 개별 필드 + 미디어 파일 받음
     * - title: 게시글 제목
     * - content: 게시글 내용
     * - files: 새로 업로드할 미디어 파일 배열 (이미지 + 영상, 선택, 기존 파일은 모두 삭제됨)
     */
    @PutMapping(value = "/{postId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResponseDTO<PostResponse>> updatePost(
            @PathVariable Long postId,
            @RequestParam("title") String title,
            @RequestParam("content") String content,
            @RequestPart(value = "files", required = false) MultipartFile[] files,
            @RequestHeader("X-User-Id") Long userId) {

        UpdatePostRequest request = new UpdatePostRequest(title, content);
        PostResponse postResponse = postService.updatePost(postId, request, files, userId);
        ResponseDTO<PostResponse> response = ResponseDTO.okWithData(postResponse, "게시글이 수정되었습니다.");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 게시글 삭제
     */
    @DeleteMapping("/{postId}")
    public ResponseEntity<ResponseDTO<Void>> deletePost(
            @PathVariable Long postId,
            @RequestHeader("X-User-Id") Long userId) {

        postService.deletePost(postId, userId);
        ResponseDTO<Void> response = ResponseDTO.okWithMessage("게시글이 삭제되었습니다.");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 게시글 단건 조회
     */
    @GetMapping("read/{postId}")
    public ResponseEntity<ResponseDTO<PostResponse>> getPost(@PathVariable Long postId) {
        PostResponse postResponse = postService.getPost(postId);
        ResponseDTO<PostResponse> response = ResponseDTO.okWithData(postResponse);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 게시글 목록 조회
     */
    @GetMapping("read")
    public ResponseEntity<ResponseDTO<List<PostResponse>>> getAllPosts() {
        List<PostResponse> postResponses = postService.getAllPosts();
        ResponseDTO<List<PostResponse>> response = ResponseDTO.okWithData(postResponses);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }
}
