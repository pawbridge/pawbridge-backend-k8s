package com.pawbridge.animalservice.controller;

import com.pawbridge.animalservice.service.ElasticsearchIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Elasticsearch 인덱스 관리 컨트롤러
 * - 초기 인덱싱, 재인덱싱, 인덱스 상태 조회
 * - 관리자 전용 엔드포인트 (추후 @PreAuthorize("hasRole('ADMIN')") 추가 필요)
 */
@Slf4j
@RestController
@RequestMapping("/api/elasticsearch")
@RequiredArgsConstructor
public class ElasticsearchIndexController {

    private final ElasticsearchIndexService indexService;

    /**
     * 전체 동물 데이터 인덱싱
     * - MySQL의 모든 동물 데이터를 Elasticsearch에 인덱싱
     * - 기존 데이터는 유지하고 새로운 데이터만 추가
     *
     * POST /api/elasticsearch/index
     */
    @PostMapping("/index")
    public ResponseEntity<Map<String, Object>> indexAllAnimals() {
        log.info("[API] 전체 동물 데이터 인덱싱 요청");

        long startTime = System.currentTimeMillis();
        long indexedCount = indexService.indexAllAnimals();
        long endTime = System.currentTimeMillis();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "인덱싱 완료");
        response.put("indexedCount", indexedCount);
        response.put("executionTimeMs", endTime - startTime);

        log.info("[API] 전체 동물 데이터 인덱싱 완료: {} 건, {} ms", indexedCount, endTime - startTime);

        return ResponseEntity.ok(response);
    }

    /**
     * 전체 재인덱싱 (기존 데이터 삭제 후 재인덱싱)
     * - 기존 Elasticsearch 인덱스 데이터 삭제
     * - MySQL 데이터로 전체 재인덱싱
     *
     * POST /api/elasticsearch/reindex
     */
    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindexAllAnimals() {
        log.info("[API] 전체 재인덱싱 요청");

        long startTime = System.currentTimeMillis();
        long indexedCount = indexService.reindexAllAnimals();
        long endTime = System.currentTimeMillis();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "재인덱싱 완료");
        response.put("indexedCount", indexedCount);
        response.put("executionTimeMs", endTime - startTime);

        log.info("[API] 전체 재인덱싱 완료: {} 건, {} ms", indexedCount, endTime - startTime);

        return ResponseEntity.ok(response);
    }

    /**
     * Elasticsearch 인덱스 상태 조회
     * - 현재 인덱스된 문서 수 조회
     *
     * GET /api/elasticsearch/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getIndexStatus() {
        log.debug("[API] Elasticsearch 인덱스 상태 조회");

        long indexedCount = indexService.getIndexedCount();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("indexedCount", indexedCount);

        return ResponseEntity.ok(response);
    }

    /**
     * Elasticsearch 인덱스 전체 삭제
     * - 모든 문서 삭제 (인덱스 자체는 유지)
     *
     * DELETE /api/elasticsearch/documents
     */
    @DeleteMapping("/documents")
    public ResponseEntity<Map<String, Object>> deleteAllDocuments() {
        log.info("[API] Elasticsearch 전체 문서 삭제 요청");

        long deletedCount = indexService.deleteAllDocuments();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "전체 문서 삭제 완료");
        response.put("deletedCount", deletedCount);

        log.info("[API] Elasticsearch 전체 문서 삭제 완료: {} 건", deletedCount);

        return ResponseEntity.ok(response);
    }
}
