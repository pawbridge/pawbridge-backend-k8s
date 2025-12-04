package com.pawbridge.communityservice.controller;

import com.pawbridge.communityservice.dto.response.PostResponse;
import com.pawbridge.communityservice.service.SearchService;
import com.pawbridge.communityservice.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 검색 Controller
 *
 * Elasticsearch를 이용한 전문 검색
 */
@RestController
@RequestMapping("/api/v1/posts/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    /**
     * 게시글 검색
     *
     * Query Parameter:
     * - keyword: 검색어 (title, content에서 검색)
     *
     * 예시: GET /api/posts/search?keyword=강아지
     */
    @GetMapping
    public ResponseEntity<ResponseDTO<List<PostResponse>>> searchPosts(@RequestParam String keyword) {
        List<PostResponse> postResponses = searchService.searchPosts(keyword);
        ResponseDTO<List<PostResponse>> response = ResponseDTO.okWithData(postResponses);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }
}
