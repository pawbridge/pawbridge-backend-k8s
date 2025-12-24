package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.domain.entity.Post;
import com.pawbridge.communityservice.domain.repository.PostRepository;
import com.pawbridge.communityservice.dto.request.CreatePostRequest;
import com.pawbridge.communityservice.dto.request.UpdatePostRequest;
import com.pawbridge.communityservice.dto.response.PostResponse;
import com.pawbridge.communityservice.exception.PostNotFoundException;
import com.pawbridge.communityservice.exception.UnauthorizedPostAccessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 게시글 서비스 구현체
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PostServiceImpl implements PostService {

    private final PostRepository postRepository;
    private final OutboxService outboxService;
    private final S3Service s3Service;
    private final UserServiceClient userServiceClient;

    /**
     * 게시글 생성
     *
     * 동작 흐름:
     * 1. 미디어 파일(이미지/영상)을 S3에 업로드
     * 2. Post 엔티티 저장 (posts 테이블, 미디어 URL 포함)
     * 3. Outbox 이벤트 저장 (outbox_events 테이블)
     * 4. Debezium이 Kafka로 발행
     * 5. Consumer가 Elasticsearch에 인덱싱
     */
    @Override
    @Transactional
    public PostResponse createPost(CreatePostRequest request, MultipartFile[] images, Long authorId) {
        // 1. 미디어 파일 S3 업로드 (이미지 + 영상)
        List<String> imageUrls = s3Service.uploadImages(images);

        // 2. Post 저장
        Post post = Post.builder()
                .authorId(authorId)
                .title(request.title())
                .content(request.content())
                .boardType(request.boardType())
                .imageUrls(imageUrls)
                .build();

        Post saved = postRepository.save(post);

        // 3. Outbox 이벤트 저장
        Map<String, Object> payload = Map.of(
                "postId", saved.getPostId(),
                "authorId", saved.getAuthorId(),
                "title", saved.getTitle(),
                "content", saved.getContent(),
                "boardType", saved.getBoardType().name(),
                "imageUrls", saved.getImageUrls()
        );

        outboxService.saveEvent(
                "Post",
                saved.getPostId().toString(),
                "POST_CREATED",
                payload
        );

        log.info("✅ Post created: postId={}, imageCount={}", saved.getPostId(), imageUrls.size());

        String authorNickname = getUserNickname(authorId);
        return PostResponse.fromEntity(saved, authorNickname);
    }

    /**
     * 게시글 수정
     *
     * 동작 흐름:
     * 1. 새로운 이미지가 있으면 기존 이미지를 S3에서 삭제
     * 2. 새로운 이미지를 S3에 업로드
     * 3. Post 엔티티 수정 (null이 아닌 필드만)
     * 4. Outbox 이벤트 저장
     */
    @Override
    @Transactional
    public PostResponse updatePost(Long postId, UpdatePostRequest request, MultipartFile[] images, Long authorId) {
        Post post = postRepository.findByPostIdAndDeletedAtIsNull(postId)
                .orElseThrow(PostNotFoundException::new);

        // 권한 체크
        if (!post.getAuthorId().equals(authorId)) {
            throw new UnauthorizedPostAccessException();
        }

        // 이미지 처리: null이 아니고 빈 배열이 아닐 때만 처리
        List<String> newImageUrls = null;
        if (images != null && images.length > 0) {
            // 기존 미디어 파일 삭제
            List<String> oldImageUrls = post.getImageUrls();
            if (oldImageUrls != null && !oldImageUrls.isEmpty()) {
                oldImageUrls.forEach(s3Service::deleteFile);
            }

            // 새로운 미디어 파일 업로드
            newImageUrls = s3Service.uploadImages(images);
        }

        // 수정 (null이 아닌 필드만 업데이트)
        post.update(request.title(), request.content(), newImageUrls);
        Post updated = postRepository.save(post);

        // Outbox 이벤트 저장
        Map<String, Object> payload = Map.of(
                "postId", updated.getPostId(),
                "authorId", updated.getAuthorId(),
                "title", updated.getTitle(),
                "content", updated.getContent(),
                "boardType", updated.getBoardType().name(),
                "imageUrls", updated.getImageUrls()
        );

        outboxService.saveEvent(
                "Post",
                updated.getPostId().toString(),
                "POST_UPDATED",
                payload
        );

        log.info("✅ Post updated: postId={}, imageUpdated={}", postId, newImageUrls != null);

        String authorNickname = getUserNickname(authorId);
        return PostResponse.fromEntity(updated, authorNickname);
    }

    /**
     * 게시글 삭제 (Soft Delete)
     *
     * 동작 흐름:
     * 1. S3에서 미디어 파일 삭제
     * 2. Post의 deleted_at 설정 (Soft Delete)
     * 3. Outbox 이벤트 저장 (Elasticsearch에서도 삭제)
     */
    @Override
    @Transactional
    public void deletePost(Long postId, Long authorId) {
        Post post = postRepository.findByPostIdAndDeletedAtIsNull(postId)
                .orElseThrow(PostNotFoundException::new);

        // 권한 체크
        if (!post.getAuthorId().equals(authorId)) {
            throw new UnauthorizedPostAccessException();
        }

        // S3에서 미디어 파일 삭제
        List<String> imageUrls = post.getImageUrls();
        if (imageUrls != null && !imageUrls.isEmpty()) {
            imageUrls.forEach(s3Service::deleteFile);
        }

        // Soft delete
        post.delete();
        postRepository.save(post);

        // Outbox 이벤트 저장
        Map<String, Object> payload = Map.of("postId", postId);

        outboxService.saveEvent(
                "Post",
                postId.toString(),
                "POST_DELETED",
                payload
        );

        log.info("✅ Post deleted: postId={}", postId);
    }

    /**
     * 게시글 단건 조회
     */
    @Override
    @Transactional(readOnly = true)
    public PostResponse getPost(Long postId) {
        Post post = postRepository.findByPostIdAndDeletedAtIsNull(postId)
                .orElseThrow(PostNotFoundException::new);

        String authorNickname = getUserNickname(post.getAuthorId());
        return PostResponse.fromEntity(post, authorNickname);
    }

    /**
     * 게시글 목록 조회
     */
    @Override
    @Transactional(readOnly = true)
    public List<PostResponse> getAllPosts() {
        return postRepository.findByDeletedAtIsNull().stream()
                .map(post -> {
                    String authorNickname = getUserNickname(post.getAuthorId());
                    return PostResponse.fromEntity(post, authorNickname);
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

    // ========== 관리자 전용 메서드 ==========

    /**
     * 전체 게시글 조회 (페이징) - 관리자용
     */
    @Override
    @Transactional(readOnly = true)
    public Page<PostResponse> getAllPostsForAdmin(Pageable pageable) {
        log.info("전체 게시글 조회 (관리자): page={}, size={}", pageable.getPageNumber(), pageable.getPageSize());

        Page<Post> posts = postRepository.findByDeletedAtIsNull(pageable);
        return posts.map(post -> {
            String authorNickname = getUserNickname(post.getAuthorId());
            return PostResponse.fromEntity(post, authorNickname);
        });
    }

    /**
     * 게시글 수정 (관리자용 - 작성자 체크 없음)
     */
    @Override
    @Transactional
    public PostResponse updatePostByAdmin(Long postId, UpdatePostRequest request, MultipartFile[] files) {
        log.info("게시글 수정 (관리자): postId={}", postId);

        // 1. 기존 게시글 조회
        Post post = postRepository.findByPostIdAndDeletedAtIsNull(postId)
                .orElseThrow(PostNotFoundException::new);

        // 2. 이미지 처리: null이 아니고 빈 배열이 아닐 때만 처리
        List<String> newImageUrls = null;
        if (files != null && files.length > 0) {
            // 기존 미디어 파일 삭제 (S3)
            if (post.getImageUrls() != null && !post.getImageUrls().isEmpty()) {
                post.getImageUrls().forEach(s3Service::deleteFile);
            }

            // 새 미디어 파일 업로드
            newImageUrls = s3Service.uploadImages(files);
        }

        // 3. Post 수정 (null이 아닌 필드만)
        post.update(request.title(), request.content(), newImageUrls);
        Post updatedPost = postRepository.save(post);

        // 4. Outbox 이벤트 저장
        Map<String, Object> payload = Map.of(
                "postId", updatedPost.getPostId(),
                "authorId", updatedPost.getAuthorId(),
                "title", updatedPost.getTitle(),
                "content", updatedPost.getContent(),
                "boardType", updatedPost.getBoardType().name(),
                "imageUrls", updatedPost.getImageUrls()
        );

        outboxService.saveEvent(
                "Post",
                postId.toString(),
                "POST_UPDATED",
                payload
        );

        String authorNickname = getUserNickname(post.getAuthorId());
        log.info("✅ Post updated by admin: postId={}, imageUpdated={}", postId, newImageUrls != null);

        return PostResponse.fromEntity(updatedPost, authorNickname);
    }

    /**
     * 게시글 삭제 (관리자용 - 작성자 체크 없음)
     */
    @Override
    @Transactional
    public void deletePostByAdmin(Long postId) {
        log.info("게시글 삭제 (관리자): postId={}", postId);

        // 1. 게시글 조회
        Post post = postRepository.findByPostIdAndDeletedAtIsNull(postId)
                .orElseThrow(PostNotFoundException::new);

        // 2. Soft Delete
        post.delete();
        postRepository.save(post);

        // 3. S3 미디어 파일 삭제
        if (post.getImageUrls() != null && !post.getImageUrls().isEmpty()) {
            post.getImageUrls().forEach(s3Service::deleteFile);
        }

        // 4. Outbox 이벤트 저장
        Map<String, Object> payload = Map.of("postId", postId);

        outboxService.saveEvent(
                "Post",
                postId.toString(),
                "POST_DELETED",
                payload
        );

        log.info("✅ Post deleted by admin: postId={}", postId);
    }
}
