package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.dto.request.CreatePostRequest;
import com.pawbridge.communityservice.dto.request.UpdatePostRequest;
import com.pawbridge.communityservice.dto.response.PostResponse;
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
}
