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

import java.util.List;
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
     * 전체 동물 데이터를 Elasticsearch에 배치 인덱싱
     * - MySQL의 모든 동물 데이터를 배치로 나눠서 Elasticsearch에 저장
     * - 메모리 효율을 위해 BATCH_SIZE(1000)건씩 처리
     * - 기존 인덱스 데이터는 유지하고 새로운 데이터만 추가 (upsert)
     *
     * @return 인덱싱된 동물 수
     */
    @Transactional(readOnly = true)
    public long indexAllAnimals() {
        log.info("[ELASTICSEARCH] 배치 인덱싱 시작 (배치 크기: {})", BATCH_SIZE);

        // 1. 전체 데이터 개수 확인
        long totalCount = animalRepository.count();
        log.info("[ELASTICSEARCH] 총 {} 건의 동물 데이터", totalCount);

        if (totalCount == 0) {
            log.warn("[ELASTICSEARCH] 인덱싱할 데이터가 없습니다");
            return 0;
        }

        // 2. 페이지 수 계산
        int totalPages = (int) Math.ceil((double) totalCount / BATCH_SIZE);
        long indexedCount = 0;

        // 3. 배치 처리
        for (int page = 0; page < totalPages; page++) {
            try {
                log.info("[ELASTICSEARCH] 배치 {}/{} 처리 중...", page + 1, totalPages);

                // 페이지별로 조회 (BATCH_SIZE만큼)
                Pageable pageable = PageRequest.of(page, BATCH_SIZE);
                Page<Animal> animalPage = animalRepository.findAll(pageable);

                // Animal → AnimalDocument 변환
                List<AnimalDocument> documents = animalPage.getContent().stream()
                    .map(this::convertToDocument)
                    .collect(Collectors.toList());

                // Elasticsearch에 저장 (Bulk API)
                animalDocumentRepository.saveAll(documents);

                indexedCount += documents.size();

                log.info("[ELASTICSEARCH] 배치 {}/{} 완료: {} 건",
                    page + 1, totalPages, documents.size());

                // 메모리 정리
                documents.clear();

            } catch (Exception e) {
                log.error("[ELASTICSEARCH] 배치 {} 실패: {}", page + 1, e.getMessage(), e);
                // 다음 배치 계속 진행
            }
        }

        log.info("[ELASTICSEARCH] 배치 인덱싱 완료: 총 {} 건", indexedCount);
        return indexedCount;
    }

    /**
     * Elasticsearch 인덱스 초기화 후 전체 재인덱싱
     * - 기존 인덱스의 모든 데이터 삭제
     * - MySQL 데이터로 재인덱싱
     *
     * @return 인덱싱된 동물 수
     */
    @Transactional(readOnly = true)
    public long reindexAllAnimals() {
        log.info("[ELASTICSEARCH] 전체 재인덱싱 시작 (기존 데이터 삭제)");

        // 1. 기존 인덱스 데이터 삭제
        long deletedCount = deleteAllDocuments();
        log.info("[ELASTICSEARCH] 기존 데이터 {} 건 삭제 완료", deletedCount);

        // 2. 전체 재인덱싱
        return indexAllAnimals();
    }

    /**
     * 특정 동물 한 건을 Elasticsearch에 인덱싱
     * - 실시간 동기화에 사용 (생성/수정 시)
     *
     * @param animal 인덱싱할 동물 엔티티
     */
    public void indexAnimal(Animal animal) {
        log.debug("[ELASTICSEARCH] 동물 인덱싱: id={}", animal.getId());

        AnimalDocument document = convertToDocument(animal);
        animalDocumentRepository.save(document);

        log.debug("[ELASTICSEARCH] 동물 인덱싱 완료: id={}", animal.getId());
    }

    /**
     * 특정 동물을 Elasticsearch에서 삭제
     * - 실시간 동기화에 사용 (삭제 시)
     *
     * @param animalId 삭제할 동물 ID
     */
    public void deleteAnimal(Long animalId) {
        log.debug("[ELASTICSEARCH] 동물 삭제: id={}", animalId);

        animalDocumentRepository.deleteById(String.valueOf(animalId));

        log.debug("[ELASTICSEARCH] 동물 삭제 완료: id={}", animalId);
    }

    /**
     * Elasticsearch 인덱스의 모든 문서 삭제
     *
     * @return 삭제된 문서 수
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
     *
     * @return 인덱스된 문서 수
     */
    public long getIndexedCount() {
        long count = animalDocumentRepository.count();
        log.debug("[ELASTICSEARCH] 현재 인덱스된 문서 수: {}", count);
        return count;
    }

    /**
     * Animal 엔티티를 AnimalDocument로 변환
     *
     * @param animal Animal 엔티티
     * @return AnimalDocument
     */
    private AnimalDocument convertToDocument(Animal animal) {
        return AnimalDocument.builder()
            .id(String.valueOf(animal.getId()))
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
            .noticeStartDate(animal.getNoticeStartDate())
            .noticeEndDate(animal.getNoticeEndDate())
            .apmsUpdatedAt(animal.getApmsUpdatedAt())
            .happenDate(animal.getHappenDate())
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
            .createdAt(animal.getCreatedAt())
            .updatedAt(animal.getUpdatedAt())
            .build();
    }
}
