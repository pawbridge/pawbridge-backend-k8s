package com.pawbridge.animalservice.service;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.pawbridge.animalservice.document.AnimalDocument;
import com.pawbridge.animalservice.dto.AnimalSearchCondition;
import com.pawbridge.animalservice.dto.request.AnimalSearchRequest;
import com.pawbridge.animalservice.dto.response.AnimalDetailResponse;
import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Species;
import com.pawbridge.animalservice.exception.AnimalNotFoundException;
import com.pawbridge.animalservice.mapper.AnimalDocumentMapper;
import com.pawbridge.animalservice.repository.AnimalDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Elasticsearch 기반 동물 조회 서비스
 * - 모든 조회 작업을 Elasticsearch에서 처리 (Elasticsearch 올인 전략)
 * - 복합 검색, 단일 조회, 목록 조회, 집계 기능 제공
 * - 페이징 및 정렬 지원
 * - 전문 검색 (키워드, 형태소 분석)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnimalElasticsearchService {

    private final ElasticsearchOperations elasticsearchOperations;
    private final AnimalDocumentRepository animalDocumentRepository;
    private final AnimalDocumentMapper documentMapper;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 단일 조회
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * ID로 동물 상세 조회 (Elasticsearch)
     * @param id 동물 ID
     * @return 동물 상세 정보
     */
    public AnimalDetailResponse findById(Long id) {
        log.debug("[ELASTICSEARCH] ID로 조회: {}", id);

        Optional<AnimalDocument> document = animalDocumentRepository.findById(String.valueOf(id));

        if (document.isEmpty()) {
            throw new AnimalNotFoundException();
        }

        return documentMapper.toDetailResponse(document.get());
    }

    /**
     * APMS 유기번호로 동물 상세 조회 (Elasticsearch)
     * @param apmsDesertionNo APMS 유기번호
     * @return 동물 상세 정보
     */
    public AnimalDetailResponse findByApmsDesertionNo(String apmsDesertionNo) {
        log.debug("[ELASTICSEARCH] APMS 유기번호로 조회: {}", apmsDesertionNo);

        // Term Query로 정확히 일치하는 문서 검색
        Query termQuery = Query.of(q -> q
            .term(t -> t
                .field("apmsDesertionNo")
                .value(apmsDesertionNo)
            )
        );

        NativeQuery nativeQuery = NativeQuery.builder()
            .withQuery(termQuery)
            .build();

        SearchHits<AnimalDocument> searchHits = elasticsearchOperations.search(nativeQuery, AnimalDocument.class);

        if (searchHits.isEmpty()) {
            throw new AnimalNotFoundException();
        }

        AnimalDocument document = searchHits.getSearchHit(0).getContent();
        return documentMapper.toDetailResponse(document);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 목록 조회
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 보호소 ID로 동물 목록 조회 (Elasticsearch)
     * @param shelterId 보호소 ID
     * @param pageable 페이징 정보
     * @return 동물 목록
     */
    public Page<AnimalResponse> findByShelterId(Long shelterId, Pageable pageable) {
        log.debug("[ELASTICSEARCH] 보호소 ID로 조회: {}, Pageable: {}", shelterId, pageable);

        Query termQuery = Query.of(q -> q
            .term(t -> t
                .field("shelterId")
                .value(shelterId)
            )
        );

        return executePagedQuery(termQuery, pageable);
    }

    /**
     * 보호소 ID + 축종으로 동물 목록 조회 (Elasticsearch)
     * @param shelterId 보호소 ID
     * @param species 축종
     * @param pageable 페이징 정보
     * @return 동물 목록
     */
    public Page<AnimalResponse> findByShelterIdAndSpecies(Long shelterId, Species species, Pageable pageable) {
        log.debug("[ELASTICSEARCH] 보호소 ID + 축종 조회: {}, {}", shelterId, species);

        BoolQuery boolQuery = BoolQuery.of(b -> b
            .must(Query.of(q -> q.term(t -> t.field("shelterId").value(shelterId))))
            .must(Query.of(q -> q.term(t -> t.field("species").value(species.name()))))
        );

        return executePagedQuery(Query.of(q -> q.bool(boolQuery)), pageable);
    }

    /**
     * 보호소 ID + 상태로 동물 목록 조회 (Elasticsearch)
     * @param shelterId 보호소 ID
     * @param status 동물 상태
     * @param pageable 페이징 정보
     * @return 동물 목록
     */
    public Page<AnimalResponse> findByShelterIdAndStatus(Long shelterId, AnimalStatus status, Pageable pageable) {
        log.debug("[ELASTICSEARCH] 보호소 ID + 상태 조회: {}, {}", shelterId, status);

        BoolQuery boolQuery = BoolQuery.of(b -> b
            .must(Query.of(q -> q.term(t -> t.field("shelterId").value(shelterId))))
            .must(Query.of(q -> q.term(t -> t.field("status").value(status.name()))))
        );

        return executePagedQuery(Query.of(q -> q.bool(boolQuery)), pageable);
    }

    /**
     * 공고 종료 임박 동물 조회 (D-3 이내) (Elasticsearch)
     * @param pageable 페이징 정보
     * @return 동물 목록 (공고 종료일 오름차순)
     */
    public Page<AnimalResponse> findExpiringSoonAnimals(Pageable pageable) {
        log.debug("[ELASTICSEARCH] 공고 종료 임박 동물 조회");

        LocalDate today = LocalDate.now();
        LocalDate threeDaysLater = today.plusDays(3);

        BoolQuery boolQuery = BoolQuery.of(b -> b
            .must(Query.of(q -> q.term(t -> t.field("status").value(AnimalStatus.PROTECT.name()))))
            .must(Query.of(q -> q.range(r -> r
                .date(d -> d
                    .field("noticeEndDate")
                    .gte(today.toString())
                    .lte(threeDaysLater.toString())
                )
            )))
        );

        // 공고 종료일 오름차순 정렬
        Pageable sortedPageable = PageRequest.of(
            pageable.getPageNumber(),
            pageable.getPageSize(),
            Sort.by(Sort.Direction.ASC, "noticeEndDate")
        );

        return executePagedQuery(Query.of(q -> q.bool(boolQuery)), sortedPageable);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 복합 검색
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 통합 검색 (Controller용)
     * - AnimalSearchRequest → AnimalSearchCondition 변환
     * - SearchHits<AnimalDocument> → Page<AnimalResponse> 변환
     *
     * @param request 검색 요청
     * @param pageable 페이징 정보
     * @return Page<AnimalResponse>
     */
    public Page<AnimalResponse> searchAnimals(AnimalSearchRequest request, Pageable pageable) {
        log.debug("[ELASTICSEARCH] 통합 검색: {}, Pageable: {}", request, pageable);

        // 1. AnimalSearchRequest → AnimalSearchCondition 변환
        AnimalSearchCondition condition = convertToCondition(request, pageable);

        // 2. Elasticsearch 검색 실행
        SearchHits<AnimalDocument> searchHits = searchAnimals(condition);

        // 3. AnimalDocument → AnimalResponse 변환
        List<AnimalResponse> responses = searchHits.getSearchHits().stream()
            .map(SearchHit::getContent)
            .map(documentMapper::toResponse)
            .collect(Collectors.toList());

        // 4. Page 객체 생성
        return new PageImpl<>(responses, pageable, searchHits.getTotalHits());
    }

    /**
     * 복합 조건으로 동물 검색 (내부 메서드)
     * @param condition 검색 조건
     * @return 검색 결과 (AnimalDocument 리스트)
     */
    private SearchHits<AnimalDocument> searchAnimals(AnimalSearchCondition condition) {
        log.debug("[ELASTICSEARCH] 검색 조건: {}", condition);

        // Bool Query 생성
        BoolQuery.Builder boolQuery = new BoolQuery.Builder();
        List<Query> mustQueries = new ArrayList<>();

        // 1. 키워드 검색 (품종, 특징, 발견 장소, 설명)
        if (condition.getKeyword() != null && !condition.getKeyword().trim().isEmpty()) {
            String keyword = condition.getKeyword().trim();

            // Multi-match query: 여러 필드에서 검색
            Query multiMatchQuery = Query.of(q -> q
                .multiMatch(m -> m
                    .query(keyword)
                    .fields("breed", "specialMark", "happenPlace", "description", "shelterName", "shelterAddress")
                    .fuzziness("AUTO")  // 오타 허용
                )
            );
            mustQueries.add(multiMatchQuery);
        }

        // 2. 축종 필터
        if (condition.getSpecies() != null && !condition.getSpecies().trim().isEmpty()) {
            Query speciesQuery = Query.of(q -> q
                .term(t -> t
                    .field("species")
                    .value(condition.getSpecies().trim())
                )
            );
            mustQueries.add(speciesQuery);
        }

        // 3. 품종 필터
        if (condition.getBreed() != null && !condition.getBreed().trim().isEmpty()) {
            Query breedQuery = Query.of(q -> q
                .match(m -> m
                    .field("breed")
                    .query(condition.getBreed().trim())
                )
            );
            mustQueries.add(breedQuery);
        }

        // 4. 상태 필터
        if (condition.getStatus() != null && !condition.getStatus().trim().isEmpty()) {
            Query statusQuery = Query.of(q -> q
                .term(t -> t
                    .field("status")
                    .value(condition.getStatus().trim())
                )
            );
            mustQueries.add(statusQuery);
        }

        // 5. 성별 필터
        if (condition.getGender() != null && !condition.getGender().trim().isEmpty()) {
            Query genderQuery = Query.of(q -> q
                .term(t -> t
                    .field("gender")
                    .value(condition.getGender().trim())
                )
            );
            mustQueries.add(genderQuery);
        }

        // 6. 중성화 필터
        if (condition.getNeuterStatus() != null && !condition.getNeuterStatus().trim().isEmpty()) {
            Query neuterQuery = Query.of(q -> q
                .term(t -> t
                    .field("neuterStatus")
                    .value(condition.getNeuterStatus().trim())
                )
            );
            mustQueries.add(neuterQuery);
        }

        // 7. 보호소 ID 필터
        if (condition.getShelterId() != null) {
            Query shelterQuery = Query.of(q -> q
                .term(t -> t
                    .field("shelterId")
                    .value(condition.getShelterId())
                )
            );
            mustQueries.add(shelterQuery);
        }

        // 8. 보호소 주소 검색 (지역 검색)
        if (condition.getShelterAddress() != null && !condition.getShelterAddress().trim().isEmpty()) {
            Query addressQuery = Query.of(q -> q
                .match(m -> m
                    .field("shelterAddress")
                    .query(condition.getShelterAddress().trim())
                )
            );
            mustQueries.add(addressQuery);
        }

        // 9. 나이 범위 (출생 연도 기준)
        if (condition.getMinBirthYear() != null && condition.getMaxBirthYear() != null) {
            // 최소/최대 둘 다 있는 경우
            Query ageRangeQuery = Query.of(q -> q
                .range(r -> r
                    .number(n -> n
                        .field("birthYear")
                        .gte(condition.getMinBirthYear().doubleValue())
                        .lte(condition.getMaxBirthYear().doubleValue())
                    )
                )
            );
            mustQueries.add(ageRangeQuery);
        } else if (condition.getMinBirthYear() != null) {
            // 최소만 있는 경우
            Query ageRangeQuery = Query.of(q -> q
                .range(r -> r
                    .number(n -> n
                        .field("birthYear")
                        .gte(condition.getMinBirthYear().doubleValue())
                    )
                )
            );
            mustQueries.add(ageRangeQuery);
        } else if (condition.getMaxBirthYear() != null) {
            // 최대만 있는 경우
            Query ageRangeQuery = Query.of(q -> q
                .range(r -> r
                    .number(n -> n
                        .field("birthYear")
                        .lte(condition.getMaxBirthYear().doubleValue())
                    )
                )
            );
            mustQueries.add(ageRangeQuery);
        }

        // Bool Query에 조건 추가
        if (!mustQueries.isEmpty()) {
            boolQuery.must(mustQueries);
        }

        // Pageable 생성 (페이징 및 정렬)
        Pageable pageable = createPageable(condition);

        // NativeQuery 생성
        NativeQuery nativeQuery = NativeQuery.builder()
            .withQuery(Query.of(q -> q.bool(boolQuery.build())))
            .withPageable(pageable)
            .build();

        // 검색 실행
        SearchHits<AnimalDocument> searchHits = elasticsearchOperations.search(nativeQuery, AnimalDocument.class);

        log.debug("[ELASTICSEARCH] 검색 결과: {} 건", searchHits.getTotalHits());

        return searchHits;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 집계 (Count)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 축종별 카운트 (Elasticsearch)
     * @param species 축종
     * @return 개수
     */
    public long countBySpecies(Species species) {
        log.debug("[ELASTICSEARCH] 축종별 카운트: {}", species);

        Query termQuery = Query.of(q -> q
            .term(t -> t
                .field("species")
                .value(species.name())
            )
        );

        return executeCountQuery(termQuery);
    }

    /**
     * 상태별 카운트 (Elasticsearch)
     * @param status 동물 상태
     * @return 개수
     */
    public long countByStatus(AnimalStatus status) {
        log.debug("[ELASTICSEARCH] 상태별 카운트: {}", status);

        Query termQuery = Query.of(q -> q
            .term(t -> t
                .field("status")
                .value(status.name())
            )
        );

        return executeCountQuery(termQuery);
    }

    /**
     * 보호소별 카운트 (Elasticsearch)
     * @param shelterId 보호소 ID
     * @return 개수
     */
    public long countByShelterId(Long shelterId) {
        log.debug("[ELASTICSEARCH] 보호소별 카운트: {}", shelterId);

        Query termQuery = Query.of(q -> q
            .term(t -> t
                .field("shelterId")
                .value(shelterId)
            )
        );

        return executeCountQuery(termQuery);
    }

    /**
     * 축종 + 상태별 카운트 (Elasticsearch)
     * @param species 축종
     * @param status 동물 상태
     * @return 개수
     */
    public long countBySpeciesAndStatus(Species species, AnimalStatus status) {
        log.debug("[ELASTICSEARCH] 축종 + 상태별 카운트: {}, {}", species, status);

        BoolQuery boolQuery = BoolQuery.of(b -> b
            .must(Query.of(q -> q.term(t -> t.field("species").value(species.name()))))
            .must(Query.of(q -> q.term(t -> t.field("status").value(status.name()))))
        );

        return executeCountQuery(Query.of(q -> q.bool(boolQuery)));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Private Helper 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Query 실행 및 Page 반환
     * @param query Elasticsearch Query
     * @param pageable 페이징 정보
     * @return Page<AnimalResponse>
     */
    private Page<AnimalResponse> executePagedQuery(Query query, Pageable pageable) {
        NativeQuery nativeQuery = NativeQuery.builder()
            .withQuery(query)
            .withPageable(pageable)
            .build();

        SearchHits<AnimalDocument> searchHits = elasticsearchOperations.search(nativeQuery, AnimalDocument.class);

        List<AnimalResponse> responses = searchHits.getSearchHits().stream()
            .map(SearchHit::getContent)
            .map(documentMapper::toResponse)
            .collect(Collectors.toList());

        return new PageImpl<>(responses, pageable, searchHits.getTotalHits());
    }

    /**
     * Count Query 실행
     * @param query Elasticsearch Query
     * @return 개수
     */
    private long executeCountQuery(Query query) {
        NativeQuery nativeQuery = NativeQuery.builder()
            .withQuery(query)
            .build();

        SearchHits<AnimalDocument> searchHits = elasticsearchOperations.search(nativeQuery, AnimalDocument.class);

        return searchHits.getTotalHits();
    }

    /**
     * Pageable 생성 (페이징 및 정렬)
     * @param condition 검색 조건
     * @return Pageable
     */
    private Pageable createPageable(AnimalSearchCondition condition) {
        // 정렬 방향
        Sort.Direction direction = "desc".equalsIgnoreCase(condition.getSortDirection())
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;

        // 정렬 기준
        Sort sort = Sort.by(direction, condition.getSortBy());

        // 페이징
        return PageRequest.of(condition.getPage(), condition.getSize(), sort);
    }

    /**
     * AnimalSearchRequest를 AnimalSearchCondition으로 변환
     * @param request 검색 요청
     * @param pageable 페이징 정보
     * @return AnimalSearchCondition
     */
    private AnimalSearchCondition convertToCondition(AnimalSearchRequest request, Pageable pageable) {
        AnimalSearchCondition.AnimalSearchConditionBuilder builder = AnimalSearchCondition.builder();

        // 키워드
        if (request.getKeyword() != null && !request.getKeyword().trim().isEmpty()) {
            builder.keyword(request.getKeyword().trim());
        }

        // 축종
        if (request.getSpecies() != null) {
            builder.species(request.getSpecies().name());
        }

        // 품종
        if (request.getBreed() != null && !request.getBreed().trim().isEmpty()) {
            builder.breed(request.getBreed().trim());
        }

        // 상태
        if (request.getStatus() != null) {
            builder.status(request.getStatus().name());
        }

        // 성별
        if (request.getGender() != null) {
            builder.gender(request.getGender().name());
        }

        // 중성화
        if (request.getNeuterStatus() != null) {
            builder.neuterStatus(request.getNeuterStatus().name());
        }

        // 나이 → 출생 연도 변환
        int currentYear = Year.now().getValue();
        if (request.getMinAge() != null) {
            builder.maxBirthYear(currentYear - request.getMinAge());  // minAge=1 → maxBirthYear=2024 (1살 이상)
        }
        if (request.getMaxAge() != null) {
            builder.minBirthYear(currentYear - request.getMaxAge());  // maxAge=5 → minBirthYear=2020 (5살 이하)
        }

        // 지역 (region, city → shelterAddress)
        StringBuilder addressBuilder = new StringBuilder();
        if (request.getRegion() != null && !request.getRegion().trim().isEmpty()) {
            addressBuilder.append(request.getRegion().trim());
        }
        if (request.getCity() != null && !request.getCity().trim().isEmpty()) {
            if (addressBuilder.length() > 0) {
                addressBuilder.append(" ");
            }
            addressBuilder.append(request.getCity().trim());
        }
        if (addressBuilder.length() > 0) {
            builder.shelterAddress(addressBuilder.toString());
        }

        // 페이징 및 정렬
        builder.page(pageable.getPageNumber());
        builder.size(pageable.getPageSize());

        // 정렬 (Pageable의 Sort 사용)
        if (pageable.getSort().isSorted()) {
            Sort.Order order = pageable.getSort().iterator().next();
            builder.sortBy(order.getProperty());
            builder.sortDirection(order.getDirection().name().toLowerCase());
        }

        return builder.build();
    }
}
