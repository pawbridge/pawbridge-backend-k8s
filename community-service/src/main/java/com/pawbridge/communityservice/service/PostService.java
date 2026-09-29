package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.dto.request.CreatePostRequest;
import com.pawbridge.communityservice.dto.request.UpdatePostRequest;
import com.pawbridge.communityservice.dto.response.PostResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 게시글 서비스 인터페이스
 */
public interface PostService {

    PostResponse createPost(CreatePostRequest request, MultipartFile[] images, Long authorId);

    PostResponse updatePost(Long postId, UpdatePostRequest request, MultipartFile[] images, Long authorId);

    void deletePost(Long postId, Long authorId);

    PostResponse getPost(Long postId);

    List<PostResponse> getAllPosts();

    // ========== 관리자 전용 메서드 ==========

    /**
     * 전체 게시글 조회 (페이징) - 관리자용
     */
    Page<PostResponse> getAllPostsForAdmin(Pageable pageable);

    /**
     * 게시글 수정 (관리자용 - 작성자 체크 없음)
     */
    PostResponse updatePostByAdmin(Long postId, UpdatePostRequest request, MultipartFile[] files);

    /**
     * 게시글 삭제 (관리자용 - 작성자 체크 없음)
     */
    void deletePostByAdmin(Long postId);

    /**
     * 오늘 작성된 게시글 수 조회 (관리자용)
     */
    Long getTodayPostCount();
}
