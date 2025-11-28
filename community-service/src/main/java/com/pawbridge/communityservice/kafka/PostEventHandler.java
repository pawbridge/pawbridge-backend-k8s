package com.pawbridge.communityservice.kafka;

import com.pawbridge.communityservice.domain.entity.ProcessedEvent;
import com.pawbridge.communityservice.domain.repository.ProcessedEventRepository;
import com.pawbridge.communityservice.elasticsearch.PostDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Post 이벤트 핸들러
 *
 * 핵심 로직:
 * 1. Idempotency 체크 (중복 방지)
 * 2. Elasticsearch 인덱싱
 * 3. processed_events 저장 (처리 완료 기록)
 *
 * Race Condition 방지:
 * - Elasticsearch 먼저, processed_events 나중
 * - 실패 시 재시도 가능하도록 순서 보장
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PostEventHandler {

    private final ProcessedEventRepository processedEventRepository;
    private final ElasticsearchOperations elasticsearchOperations;

    /**
     * POST_CREATED: Elasticsearch 인덱싱
     */
    @Transactional
    public void indexPost(String eventId, Map<String, Object> payload) {
        // 1. 중복 체크 (Idempotency)
        if (processedEventRepository.existsByEventId(eventId)) {
            log.warn("⚠️ Already processed: eventId={}", eventId);
            return;
        }

        // 2. Elasticsearch 인덱싱 먼저 (실패 시 재시도 가능)
        PostDocument document = PostDocument.builder()
                .postId(((Number) payload.get("postId")).longValue())
                .authorId(((Number) payload.get("authorId")).longValue())
                .title((String) payload.get("title"))
                .content((String) payload.get("content"))
                .boardType((String) payload.get("boardType"))
                .imageUrls((List<String>) payload.get("imageUrls"))
                .build();

        elasticsearchOperations.save(document);

        // 3. 성공 시에만 processed_events 저장
        processedEventRepository.save(ProcessedEvent.of(eventId, "POST_CREATED"));

        log.info("✅ Indexed post: postId={}, eventId={}", document.getPostId(), eventId);
    }

    /**
     * POST_UPDATED: Elasticsearch 업데이트
     */
    @Transactional
    public void updatePost(String eventId, Map<String, Object> payload) {
        if (processedEventRepository.existsByEventId(eventId)) {
            log.warn("⚠️ Already processed: eventId={}", eventId);
            return;
        }

        Long postId = ((Number) payload.get("postId")).longValue();

        PostDocument document = PostDocument.builder()
                .postId(postId)
                .authorId(((Number) payload.get("authorId")).longValue())
                .title((String) payload.get("title"))
                .content((String) payload.get("content"))
                .boardType((String) payload.get("boardType"))
                .imageUrls((List<String>) payload.get("imageUrls"))
                .build();

        elasticsearchOperations.save(document);
        processedEventRepository.save(ProcessedEvent.of(eventId, "POST_UPDATED"));

        log.info("✅ Updated post: postId={}, eventId={}", postId, eventId);
    }

    /**
     * POST_DELETED: Elasticsearch 문서 삭제
     */
    @Transactional
    public void deletePost(String eventId, Map<String, Object> payload) {
        if (processedEventRepository.existsByEventId(eventId)) {
            log.warn("⚠️ Already processed: eventId={}", eventId);
            return;
        }

        Long postId = ((Number) payload.get("postId")).longValue();

        elasticsearchOperations.delete(String.valueOf(postId), PostDocument.class);
        processedEventRepository.save(ProcessedEvent.of(eventId, "POST_DELETED"));

        log.info("✅ Deleted post: postId={}, eventId={}", postId, eventId);
    }
}
