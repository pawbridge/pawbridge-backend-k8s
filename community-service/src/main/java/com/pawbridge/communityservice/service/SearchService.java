package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.dto.response.PostResponse;

import java.util.List;

/**
 * 검색 서비스 인터페이스
 */
public interface SearchService {

    /**
     * Elasticsearch를 이용한 게시글 전문 검색
     * - title, content에서 keyword 검색
     * - nori 한국어 형태소 분석기 사용
     */
    List<PostResponse> searchPosts(String keyword);
}
