package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.document.AnimalDocument;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.exception.ConcurrentRequestException;
import com.pawbridge.animalservice.repository.AnimalDocumentRepository;
import com.pawbridge.animalservice.repository.AnimalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.index.AliasActions;
import org.springframework.data.elasticsearch.core.index.AliasAction;
import org.springframework.data.elasticsearch.core.index.AliasActionParameters;
import org.springframework.data.elasticsearch.core.index.AliasData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Elasticsearch 초기 인덱싱 및 동기화 서비스
 * - MySQL → Elasticsearch 일괄 인덱싱
 * - 인덱스 초기화 및 재인덱싱
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElasticsearchIndexService {

    private final AnimalRepository animalRepository;
    private final AnimalDocumentRepository animalDocumentRepository;
    private final ElasticsearchOperations elasticsearchOperations;
    private final RedissonClient redissonClient;
    private final PlatformTransactionManager transactionManager;

    // Step 2 병렬 인덱싱용 — ApmsAnimalBatchJob에서 정의한 batchTaskExecutor 주입
    @Autowired
    @Qualifier("batchTaskExecutor")
    private Executor batchTaskExecutor;

    private static final int BATCH_SIZE = 1000;  // 배치 크기
    private static final String REINDEX_LOCK_KEY = "lock:reindexAllAnimals";
    private static final String INDEX_NAME = "animals";

    /**
     * 전체 동물 데이터를 Elasticsearch에 배치 인덱싱 (기존 동작 호환)
     */
    @Transactional(readOnly = true)
    public long indexAllAnimals() {
        return indexAnimalsToTarget("animals");
    }

    /**
     * 지정된 대상 인덱스에 데이터 병렬 적재
     * - 페이지별로 CompletableFuture 생성 → batchTaskExecutor 스레드 풀에서 병렬 실행
     * - 각 스레드: TransactionTemplate(readOnly)으로 독립 트랜잭션 → DB 조회 → ES 벌크 인덱싱
     * - 한 페이지라도 실패 시 CompletionException → IllegalStateException → 상위에서 신규 인덱스 롤백 삭제
     */
    private long indexAnimalsToTarget(String targetIndexName) {
        log.info("[ELASTICSEARCH] 대상 인덱스 [{}]에 데이터 적재 시작 (배치 크기: {})", targetIndexName, BATCH_SIZE);
        IndexCoordinates coordinates = IndexCoordinates.of(targetIndexName);

        long totalCount = animalRepository.count();
        log.info("[ELASTICSEARCH] 총 {} 건의 동물 데이터 조회됨", totalCount);

        if (totalCount == 0) {
            log.warn("[ELASTICSEARCH] 인덱싱할 데이터가 없습니다");
            return 0;
        }

        int totalPages = (int) Math.ceil((double) totalCount / BATCH_SIZE);
        AtomicLong indexedCount = new AtomicLong(0);
        AtomicInteger completedPages = new AtomicInteger(0);

        TransactionTemplate readOnlyTx = new TransactionTemplate(transactionManager);
        readOnlyTx.setReadOnly(true);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int page = 0; page < totalPages; page++) {
            final int p = page;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                Pageable pageable = PageRequest.of(p, BATCH_SIZE);

                // 각 스레드에서 독립 readOnly 트랜잭션으로 DB 조회
                // execute() 반환 타입이 @Nullable이므로 null 방어 처리
                List<AnimalDocument> documents = Objects.requireNonNullElseGet(
                        readOnlyTx.execute(status -> {
                            Page<Animal> animalPage = animalRepository.findAllWithShelter(pageable);
                            return animalPage.getContent().stream()
                                    .map(this::convertToDocument)
                                    .collect(Collectors.toList());
                        }),
                        Collections::emptyList
                );

                // ES 벌크 upsert (doc_as_upsert: true → image_vector 등 기존 필드 보존)
                List<UpdateQuery> updateQueries = documents.stream()
                        .map(doc -> UpdateQuery.builder(doc.getEsId())
                                .withDocument(Document.from(buildDocumentFields(doc)))
                                .withDocAsUpsert(true)
                                .build())
                        .collect(Collectors.toList());
                elasticsearchOperations.bulkUpdate(updateQueries, coordinates);
                indexedCount.addAndGet(documents.size());
                log.info("[ELASTICSEARCH] 배치 {}/{} 완료: {} 건",
                        completedPages.incrementAndGet(), totalPages, documents.size());

            }, batchTaskExecutor);
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("[ELASTICSEARCH] 병렬 인덱싱 중 실패 발생 - 중단합니다.", cause);
            throw new IllegalStateException("병렬 배치 인덱싱 실패 - 데이터 유실 방지를 위해 중단합니다.", cause);
        }

        log.info("[ELASTICSEARCH] 적재 완료: 총 {} 건", indexedCount.get());
        return indexedCount.get();
    }

    /**
     * 무중단 전체 재인덱싱 (Alias Swap 패턴)
     * - 신규 인덱스 생성 (Template 자동 적용) -> 적재 -> 별칭 원자적 교체 -> 구 인덱스 삭제
     */
    public long reindexAllAnimals() {
        RLock lock = redissonClient.getLock(REINDEX_LOCK_KEY);
        boolean isLocked = false;
        try {
            // 대기시간 0초(즉시 튕겨냄), 락 유지 10분 설정 (서버가 강제 종료되어도 10분 뒤 자동 해제)
            isLocked = lock.tryLock(0, 10, TimeUnit.MINUTES);
            if (!isLocked) {
                throw new ConcurrentRequestException();
            }
            log.info("[ELASTICSEARCH] 재인덱싱 분산 락 획득 완료");

            String newIndexName = null;
            try {
                String aliasName = "animals";
                newIndexName = "animals_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

                log.info("[ELASTICSEARCH] Alias Swap 방식 재인덱싱 시작 - 신규 인덱스 할당: {}", newIndexName);

                // 1. 신규 인덱스 생성 (ES에 사전 등록된 animals_template 적용)
                IndexOperations indexOps = elasticsearchOperations.indexOps(IndexCoordinates.of(newIndexName));
                indexOps.create();
                log.info("[ELASTICSEARCH] 신규 인덱스 생성 완료: {}", newIndexName);

                // 2. 신규 인덱스에 데이터 적재 (실패 시 예외 발생)
                long count = indexAnimalsToTarget(newIndexName);

                // 3. 기존 'animals' 별칭을 가진 구버전 인덱스 탐색
                Set<String> oldIndexNames = getIndexNamesByAlias(aliasName);
                log.info("[ELASTICSEARCH] 기존 연결된 인덱스 탐색 결과: {}", oldIndexNames != null && !oldIndexNames.isEmpty() ? oldIndexNames : "없음 (최초 실행)");

                // 4. 별칭 atomic swap (원자적 교체)
                AliasActions aliasActions = new AliasActions();
                if (oldIndexNames != null && !oldIndexNames.isEmpty()) {
                    for (String oldIndexName : oldIndexNames) {
                        aliasActions.add(new AliasAction.Remove(AliasActionParameters.builder().withIndices(oldIndexName).withAliases(aliasName).build()));
                    }
                }
                aliasActions.add(new AliasAction.Add(AliasActionParameters.builder().withIndices(newIndexName).withAliases(aliasName).build()));
                
                indexOps.alias(aliasActions);
                log.info("[ELASTICSEARCH] 별칭 Swap 완료! ({} -> {})", oldIndexNames != null && !oldIndexNames.isEmpty() ? oldIndexNames : "N/A", newIndexName);

                // 5. 구버전 인덱스 폐기
                if (oldIndexNames != null && !oldIndexNames.isEmpty()) {
                    for (String oldIndexName : oldIndexNames) {
                        if (!oldIndexName.equals(newIndexName)) {
                            elasticsearchOperations.indexOps(IndexCoordinates.of(oldIndexName)).delete();
                            log.info("[ELASTICSEARCH] 구버전 인덱스 [{}] 삭제 완료", oldIndexName);
                        }
                    }
                }

                return count;
                
            } catch (Exception e) {
                log.error("[ELASTICSEARCH] 재인덱싱 중 치명적 오류 발생. 신규 인덱스가 승격되지 않도록 롤백합니다.", e);
                // 실패한 경우 만들어둔 쓰레기 인덱스 정리
                if (newIndexName != null) {
                    try {
                        elasticsearchOperations.indexOps(IndexCoordinates.of(newIndexName)).delete();
                        log.info("[ELASTICSEARCH] 실패한 신규 인덱스 폐기 완료: {}", newIndexName);
                    } catch (Exception deleteEx) {
                        log.warn("[ELASTICSEARCH] 실패한 인덱스 폐기 중 오류 발생 (무시됨): {}", deleteEx.getMessage());
                    }
                }
                throw e;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConcurrentRequestException();
        } finally {
            if (isLocked && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("[ELASTICSEARCH] 재인덱싱 분산 락 해제 완료");
            }
        }
    }

    /**
     * 특정 별칭(Alias)에 현재 연결된 모든 실제 인덱스 조회
     */
    private Set<String> getIndexNamesByAlias(String aliasName) {
        try {
            // 모든 하위 인덱스 및 별칭 정보 조회
            IndexOperations indexOps = elasticsearchOperations.indexOps(IndexCoordinates.of("_all"));
            Map<String, Set<AliasData>> aliasesMap = indexOps.getAliases();

            if (aliasesMap == null || aliasesMap.isEmpty()) {
                return Collections.emptySet();
            }

            // 요청된 aliasName을 실제로 가지고 있는 인덱스명(Key)만 필터링하여 반환
            return aliasesMap.entrySet().stream()
                .filter(entry -> entry.getValue().stream()
                    .anyMatch(aliasData -> aliasName.equals(aliasData.getAlias())))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        } catch (org.springframework.data.elasticsearch.NoSuchIndexException e) {
            log.info("[ELASTICSEARCH] '{}' 이름을 가진 인덱스나 별칭이 존재하지 않습니다. (최초 실행)", aliasName);
            return Collections.emptySet();
        } catch (Exception e) {
            log.error("[ELASTICSEARCH] 별칭 조회 중 예상치 못한 인프라 오류 발생: {}", e.getMessage());
            throw new IllegalStateException("ES 별칭 정보를 조회할 수 없어 안전을 위해 재인덱싱을 중단합니다.", e);
        }
    }

    /**
     * 특정 동물 한 건을 Elasticsearch에 upsert
     * - doc_as_upsert: true → 기존 문서 partial update (image_vector 보존)
     */
    public void indexAnimal(Animal animal) {
        log.debug("[ELASTICSEARCH] 동물 인덱싱: id={}", animal.getId());
        AnimalDocument document = convertToDocument(animal);
        UpdateQuery updateQuery = UpdateQuery.builder(document.getEsId())
                .withDocument(Document.from(buildDocumentFields(document)))
                .withDocAsUpsert(true)
                .build();
        elasticsearchOperations.update(updateQuery, IndexCoordinates.of(INDEX_NAME));
        log.debug("[ELASTICSEARCH] 동물 인덱싱 완료: id={}", animal.getId());
    }

    /**
     * 특정 동물을 Elasticsearch에서 삭제
     */
    public void deleteAnimal(Long animalId) {
        log.debug("[ELASTICSEARCH] 동물 삭제: id={}", animalId);
        animalDocumentRepository.deleteById(String.valueOf(animalId));
        log.debug("[ELASTICSEARCH] 동물 삭제 완료: id={}", animalId);
    }

    /**
     * Elasticsearch 인덱스의 모든 문서 삭제
     */
    public long deleteAllDocuments() {
        log.info("[ELASTICSEARCH] 전체 문서 삭제 시작");
        long count = animalDocumentRepository.count();
        animalDocumentRepository.deleteAll();
        log.info("[ELASTICSEARCH] {} 건의 문서 삭제 완료", count);
        return count;
    }

    /**
     * Elasticsearch 인덱스 상태 조회
     */
    public long getIndexedCount() {
        long count = animalDocumentRepository.count();
        log.debug("[ELASTICSEARCH] 현재 인덱스된 문서 수: {}", count);
        return count;
    }

    /**
     * Animal 엔티티를 AnimalDocument로 변환
     * - esId = MySQL PK (String): upsert 시 동일 _id로 기존 문서 업데이트 (image_vector 보존)
     */
    private AnimalDocument convertToDocument(Animal animal) {
        return AnimalDocument.builder()
            .esId(String.valueOf(animal.getId())) // ES _id = MySQL PK → upsert 키로 사용
            .id(animal.getId()) // MySQL PK (Long) -> 'id' 필드에 매핑됨
            .apmsDesertionNo(animal.getApmsDesertionNo())
            .apmsNoticeNo(animal.getApmsNoticeNo())
            .species(animal.getSpecies() != null ? animal.getSpecies().name() : null)
            .breed(animal.getBreed())
            .birthYear(animal.getBirthYear())
            .weight(animal.getWeight())
            .color(animal.getColor())
            .gender(animal.getGender() != null ? animal.getGender().name() : null)
            .neuterStatus(animal.getNeuterStatus() != null ? animal.getNeuterStatus().name() : null)
            .specialMark(animal.getSpecialMark())
            .apmsProcessState(animal.getApmsProcessState())
            .noticeStartDate(toStringFormat(animal.getNoticeStartDate()))
            .noticeEndDate(toStringFormat(animal.getNoticeEndDate()))
            .apmsUpdatedAt(toStringFormat(animal.getApmsUpdatedAt()))
            .happenDate(toStringFormat(animal.getHappenDate()))
            .happenPlace(animal.getHappenPlace())
            .imageUrl(animal.getImageUrl())
            .imageUrl2(animal.getImageUrl2())
            .shelterId(animal.getShelter() != null ? animal.getShelter().getId() : null)
            .shelterName(animal.getShelter() != null ? animal.getShelter().getName() : null)
            .shelterAddress(animal.getShelter() != null ? animal.getShelter().getAddress() : null)
            .shelterPhone(animal.getShelter() != null ? animal.getShelter().getPhone() : null)
            .status(animal.getStatus() != null ? animal.getStatus().name() : null)
            .apiSource(animal.getApiSource() != null ? animal.getApiSource().name() : null)
            .favoriteCount(animal.getFavoriteCount())
            .description(animal.getDescription())
            .createdAt(toStringFormat(animal.getCreatedAt()))
            .updatedAt(toStringFormat(animal.getUpdatedAt()))
            .build();
    }

    /**
     * AnimalDocument → ES 업데이트용 필드 맵 변환
     * - image_vector 만 제외 (Python AI Service가 관리, 보존 필요)
     * - 나머지 nullable 필드는 null 포함하여 ES 정합성 유지
     */
    private Map<String, Object> buildDocumentFields(AnimalDocument doc) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", doc.getId());
        fields.put("apms_desertion_no", doc.getApmsDesertionNo());
        fields.put("apms_notice_no", doc.getApmsNoticeNo());
        fields.put("species", doc.getSpecies());
        fields.put("breed", doc.getBreed());
        fields.put("birth_year", doc.getBirthYear());
        fields.put("weight", doc.getWeight());
        fields.put("color", doc.getColor());
        fields.put("gender", doc.getGender());
        fields.put("neuter_status", doc.getNeuterStatus());
        fields.put("special_mark", doc.getSpecialMark());
        fields.put("apms_process_state", doc.getApmsProcessState());
        fields.put("notice_start_date", doc.getNoticeStartDate());
        fields.put("notice_end_date", doc.getNoticeEndDate());
        fields.put("apms_updated_at", doc.getApmsUpdatedAt());
        fields.put("happen_date", doc.getHappenDate());
        fields.put("happen_place", doc.getHappenPlace());
        fields.put("image_url", doc.getImageUrl());
        fields.put("image_url2", doc.getImageUrl2());
        fields.put("shelter_id", doc.getShelterId());
        fields.put("shelter_name", doc.getShelterName());
        fields.put("shelter_address", doc.getShelterAddress());
        fields.put("shelter_phone", doc.getShelterPhone());
        fields.put("status", doc.getStatus());
        fields.put("api_source", doc.getApiSource());
        fields.put("favorite_count", doc.getFavoriteCount());
        fields.put("description", doc.getDescription());
        fields.put("created_at", doc.getCreatedAt());
        fields.put("updated_at", doc.getUpdatedAt());
        // image_vector는 포함하지 않음 (Python AI Service가 관리)
        return fields;
    }

    private String toStringFormat(LocalDate date) {
        if (date == null) return null;
        return date.toString();
    }

    private String toStringFormat(LocalDateTime dateTime) {
        if (dateTime == null) return null;
        return dateTime.toString();
    }
}
