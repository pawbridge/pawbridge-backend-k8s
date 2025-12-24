package com.pawbridge.communityservice.controller;

import com.pawbridge.communityservice.dto.request.UpdatePostRequest;
import com.pawbridge.communityservice.dto.response.PostResponse;
import com.pawbridge.communityservice.service.PostService;
import com.pawbridge.communityservice.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 관리자 - 게시글 관리 Controller
 *
 * Gateway에서 /api/admin/posts로 들어온 요청이 /api/v1/admin/posts로 rewrite되어 들어옴
 * 인증 및 권한 체크: API Gateway의 JWT 필터에서 ROLE_ADMIN 체크
 */
@RestController
@RequestMapping("/api/v1/admin/posts")
@RequiredArgsConstructor
@Slf4j
public class AdminPostController {

    private final PostService postService;

    /**
     * 관리자 - 전체 게시글 조회 (페이징)
     */
    @GetMapping
    public ResponseEntity<ResponseDTO<Page<PostResponse>>> getAllPostsForAdmin(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        log.info("관리자 - 전체 게시글 조회: page={}, size={}", pageable.getPageNumber(), pageable.getPageSize());

        Page<PostResponse> posts = postService.getAllPostsForAdmin(pageable);
        ResponseDTO<Page<PostResponse>> response = ResponseDTO.okWithData(posts);

        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 관리자 - 특정 게시글 조회
     */
    @GetMapping("/{postId}")
    public ResponseEntity<ResponseDTO<PostResponse>> getPostForAdmin(@PathVariable Long postId) {

        log.info("관리자 - 게시글 조회: postId={}", postId);

        PostResponse postResponse = postService.getPost(postId);
        ResponseDTO<PostResponse> response = ResponseDTO.okWithData(postResponse);

        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 관리자 - 게시글 수정 (작성자 체크 없음)
     * - title, content, files 모두 선택적
     * - null이 아닌 필드만 업데이트됨
     */
    @PutMapping(value = "/{postId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResponseDTO<PostResponse>> updatePostByAdmin(
            @PathVariable Long postId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "content", required = false) String content,
            @RequestPart(value = "files", required = false) MultipartFile[] files) {

        log.info("관리자 - 게시글 수정: postId={}", postId);

        UpdatePostRequest request = new UpdatePostRequest(title, content);
        PostResponse postResponse = postService.updatePostByAdmin(postId, request, files);
        ResponseDTO<PostResponse> response = ResponseDTO.okWithData(postResponse, "게시글이 수정되었습니다.");

        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 관리자 - 게시글 삭제 (작성자 체크 없음)
     */
    @DeleteMapping("/{postId}")
    public ResponseEntity<ResponseDTO<Void>> deletePostByAdmin(@PathVariable Long postId) {

        log.info("관리자 - 게시글 삭제: postId={}", postId);

        postService.deletePostByAdmin(postId);
        ResponseDTO<Void> response = ResponseDTO.okWithMessage("게시글이 삭제되었습니다.");

        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }
}
