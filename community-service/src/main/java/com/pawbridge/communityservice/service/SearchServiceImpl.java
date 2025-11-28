package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.domain.entity.BoardType;
import com.pawbridge.communityservice.domain.repository.PostRepository;
import com.pawbridge.communityservice.dto.response.PostResponse;
import com.pawbridge.communityservice.elasticsearch.PostDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 검색 서비스 구현체
 *
 * Elasticsearch 전문 검색:
 * - nori 분석기로 한국어 형태소 분석
 * - title, content 필드에서 키워드 검색
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchServiceImpl implements SearchService {

    private final ElasticsearchOperations elasticsearchOperations;
    private final PostRepository postRepository;

    /**
     * 게시글 검색
     *
     * 검색 로직:
     * 1. Elasticsearch에서 title 또는 content에 keyword 포함된 문서 검색
     * 2. postId 목록 추출
     * 3. MySQL에서 실제 데이터 조회 (최신 데이터 보장)
     *
     * nori 분석기 적용:
     * - 한국어 형태소 분석으로 부분 검색 가능
     * - 예: "지산이를" → ["지산", "이", "를"] 토큰화
     */
    @Override
    public List<PostResponse> searchPosts(String keyword) {
        log.info("🔍 Searching posts with keyword: {}", keyword);

        // Elasticsearch 검색 쿼리 (multi_match로 title, content 동시 검색)
        // analyzer: "nori" 명시 (인덱싱과 동일한 분석기 사용)
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q
                        .multiMatch(m -> m
                                .query(keyword)
                                .fields("title", "content")
                                .analyzer("nori")  // 검색 시에도 nori 분석기 사용
                        )
                )
                .build();

        // Elasticsearch 검색 실행
        SearchHits<PostDocument> searchHits = elasticsearchOperations.search(query, PostDocument.class);

        // postId 목록 추출
        List<Long> postIds = searchHits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .map(PostDocument::getPostId)
                .collect(Collectors.toList());

        log.info("✅ Found {} posts in Elasticsearch", postIds.size());

        // postId가 없으면 빈 리스트 반환
        if (postIds.isEmpty()) {
            return List.of();
        }

        // MySQL에서 실제 데이터 조회 (삭제되지 않은 것만)
        return postRepository.findAllById(postIds).stream()
                .filter(post -> post.getDeletedAt() == null)
                .map(PostResponse::fromEntity)
                .collect(Collectors.toList());
    }
}
