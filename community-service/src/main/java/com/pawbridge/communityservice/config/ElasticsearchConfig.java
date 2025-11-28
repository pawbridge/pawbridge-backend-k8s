package com.pawbridge.communityservice.config;

import com.pawbridge.communityservice.elasticsearch.PostDocument;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;

/**
 * Elasticsearch 설정
 *
 * 목적:
 * 1. 인덱스 자동 생성 (posts)
 * 2. nori 한국어 형태소 분석기 설정
 * 3. 연결 실패 시 재시도
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class ElasticsearchConfig {

    private final ElasticsearchOperations elasticsearchOperations;

    /**
     * 애플리케이션 시작 시 인덱스 생성
     *
     * Retry 로직:
     * - Elasticsearch가 늦게 시작될 수 있으므로 10회 재시도
     * - 5초 간격
     */
    @PostConstruct
    public void createIndexWithMapping() {
        int maxRetries = 10;
        int delay = 5000;

        for (int i = 0; i < maxRetries; i++) {
            try {
                IndexOperations indexOps = elasticsearchOperations.indexOps(PostDocument.class);

                // 인덱스가 이미 존재하면 스킵
                if (indexOps.exists()) {
                    log.info("✅ Elasticsearch index 'posts' already exists");
                    return;
                }

                // 인덱스 생성 (PostDocument의 @Setting과 @Field 애노테이션 사용)
                indexOps.create();
                indexOps.putMapping(indexOps.createMapping());

                log.info("✅ Elasticsearch index 'posts' created with nori analyzer");
                return;

            } catch (Exception e) {
                log.warn("⚠️ Failed to create Elasticsearch index (attempt {}/{}): {}",
                        i + 1, maxRetries, e.getMessage());

                if (i == maxRetries - 1) {
                    throw new RuntimeException("Failed to create Elasticsearch index after " + maxRetries + " retries", e);
                }

                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting to retry", ie);
                }
            }
        }
    }
}
