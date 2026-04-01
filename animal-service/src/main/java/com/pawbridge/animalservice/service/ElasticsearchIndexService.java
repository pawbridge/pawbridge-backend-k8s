package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.document.AnimalDocument;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.repository.AnimalDocumentRepository;
import com.pawbridge.animalservice.repository.AnimalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.index.AliasActions;
import org.springframework.data.elasticsearch.core.index.AliasAction;
import org.springframework.data.elasticsearch.core.index.AliasActionParameters;
import org.springframework.data.elasticsearch.core.index.AliasMetaData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    private static final int BATCH_SIZE = 1000;  // 배치 크기

    /**
     * 전체 동물 데이터를 Elasticsearch에 배치 인덱싱 (기존 동작 호환)
     */
    @Transactional(readOnly = true)
    public long indexAllAnimals() {
        return indexAnimalsToTarget("animals");
    }

    /**
     * 지정된 대상 인덱스에 데이터 적재 (Repository 대신 IndexCoordinates로 명시적 분기)
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
        long indexedCount = 0;

        for (int page = 0; page < totalPages; page++) {
            try {
                Pageable pageable = PageRequest.of(page, BATCH_SIZE);
                Page<Animal> animalPage = animalRepository.findAllWithShelter(pageable);

                List<AnimalDocument> documents = animalPage.getContent().stream()
                    .map(this::convertToDocument)
                    .collect(Collectors.toList());

                // 특정 타겟 인덱스(또는 별칭)에 bulk insert
                elasticsearchOperations.save(documents, coordinates);
                indexedCount += documents.size();

                log.info("[ELASTICSEARCH] 배치 {}/{} 완료: {} 건", page + 1, totalPages, documents.size());
                documents.clear();
            } catch (Exception e) {
                log.error("[ELASTICSEARCH] 배치 {} 실패: {}", page + 1, e.getMessage(), e);
            }
        }

        log.info("[ELASTICSEARCH] 적재 완료: 총 {} 건", indexedCount);
        return indexedCount;
    }

    /**
     * 무중단 전체 재인덱싱 (Alias Swap 패턴)
     * - 신규 인덱스 생성 (Template 자동 적용) -> 적재 -> 별칭 원자적 교체 -> 구 인덱스 삭제
     */
    @Transactional(readOnly = true)
    public long reindexAllAnimals() {
        String aliasName = "animals";
        String newIndexName = "animals_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        log.info("[ELASTICSEARCH] Alias Swap 방식 재인덱싱 시작 - 신규 인덱스 할당: {}", newIndexName);

        // 1. 신규 인덱스 생성 (ES에 사전 등록된 animals_template 적용)
        IndexOperations indexOps = elasticsearchOperations.indexOps(IndexCoordinates.of(newIndexName));
        indexOps.create();
        log.info("[ELASTICSEARCH] 신규 인덱스 생성 완료: {}", newIndexName);

        // 2. 신규 인덱스에 데이터 적재
        long count = indexAnimalsToTarget(newIndexName);

        // 3. 기존 'animals' 별칭을 가진 구버전 인덱스 탐색
        String oldIndexName = getIndexNameByAlias(aliasName);
        log.info("[ELASTICSEARCH] 기존 연결된 인덱스 탐색 결과: {}", oldIndexName != null ? oldIndexName : "없음 (최초 실행)");

        // 4. 별칭 atomic swap (원자적 교체)
        AliasActions aliasActions = new AliasActions();
        if (oldIndexName != null) {
            aliasActions.add(new AliasAction.Remove(AliasActionParameters.builder().withIndices(oldIndexName).withAliases(aliasName).build()));
        }
        aliasActions.add(new AliasAction.Add(AliasActionParameters.builder().withIndices(newIndexName).withAliases(aliasName).build()));
        
        indexOps.alias(aliasActions);
        log.info("[ELASTICSEARCH] 별칭 Swap 완료! ({} -> {})", oldIndexName != null ? oldIndexName : "N/A", newIndexName);

        // 5. 구버전 인덱스 폐기
        if (oldIndexName != null && !oldIndexName.equals(newIndexName)) {
            elasticsearchOperations.indexOps(IndexCoordinates.of(oldIndexName)).delete();
            log.info("[ELASTICSEARCH] 구버전 인덱스 [{}] 삭제 완료", oldIndexName);
        }

        return count;
    }

    /**
     * 특정 별칭(Alias)이 현재 어떤 실제 인덱스를 가리키고 있는지 조회
     */
    private String getIndexNameByAlias(String aliasName) {
        try {
            IndexOperations aliasOps = elasticsearchOperations.indexOps(IndexCoordinates.of(aliasName));
            Map<String, Set<AliasMetaData>> aliases = aliasOps.getAliases();
            if (aliases != null && !aliases.isEmpty()) {
                return aliases.keySet().stream().findFirst().orElse(null);
            }
        } catch (Exception e) {
            log.warn("[ELASTICSEARCH] 별칭 조회 실패 (최초 실행이거나 일반 인덱스일 수 있음): {}", e.getMessage());
        }
        return null;
    }

    /**
     * 특정 동물 한 건을 Elasticsearch에 인덱싱
     */
    public void indexAnimal(Animal animal) {
        log.debug("[ELASTICSEARCH] 동물 인덱싱: id={}", animal.getId());
        AnimalDocument document = convertToDocument(animal);
        animalDocumentRepository.save(document);
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
     */
    private AnimalDocument convertToDocument(Animal animal) {
        return AnimalDocument.builder()
            .id(animal.getId()) // MySQL PK (Long) -> 'id' 필드에 매핑됨
            // .esId(null) // ES _id는 자동 생성
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

    private String toStringFormat(LocalDate date) {
        if (date == null) return null;
        return date.toString();
    }

    private String toStringFormat(LocalDateTime dateTime) {
        if (dateTime == null) return null;
        return dateTime.toString();
    }
}
