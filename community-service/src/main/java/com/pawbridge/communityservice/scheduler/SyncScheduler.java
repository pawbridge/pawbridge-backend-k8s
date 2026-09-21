package com.pawbridge.communityservice.scheduler;

import com.pawbridge.communityservice.domain.entity.Post;
import com.pawbridge.communityservice.domain.repository.PostRepository;
import com.pawbridge.communityservice.elasticsearch.PostDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sync Scheduler: MySQL → Elasticsearch 동기화
 *
 * 목적: Kafka 실패 시 누락된 문서 복구
 * - 매일 새벽 2시 실행
 * - MySQL의 모든 게시글을 Elasticsearch와 비교
 * - 누락된 문서 재인덱싱
 *
 * 참고: Eventual Consistency 보장
 */
@Component
@RequiredArgsConstructor
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="pawbridge.search.backend",havingValue="elasticsearch",matchIfMissing=true)
public class SyncScheduler {

    private final PostRepository postRepository;
    private final ElasticsearchOperations elasticsearchOperations;

    /**
     * 매일 새벽 2시: MySQL → Elasticsearch 동기화
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void syncPostsToElasticsearch() {
        log.info("🔄 Starting MySQL → Elasticsearch sync");

        List<Post> posts = postRepository.findByDeletedAtIsNullOrderByCreatedAtDesc();
        int synced = 0;

        for (Post post : posts) {
            try {
                // Elasticsearch에 문서가 없으면 재인덱싱
                if (!elasticsearchOperations.exists(String.valueOf(post.getPostId()), PostDocument.class)) {
                    PostDocument document = PostDocument.builder()
                            .postId(post.getPostId())
                            .authorId(post.getAuthorId())
                            .title(post.getTitle())
                            .content(post.getContent())
                            .boardType(post.getBoardType().name())
                            .imageUrls(post.getImageUrls())
                            .build();

                    elasticsearchOperations.save(document);
                    synced++;
                }
            } catch (Exception e) {
                log.error("❌ Failed to sync post: postId={}", post.getPostId(), e);
            }
        }

        log.info("✅ Sync completed: {} posts synced", synced);
    }
}
