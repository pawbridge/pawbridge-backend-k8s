package com.pawbridge.animalservice.service;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

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
@ConditionalOnProperty(prefix = "pawbridge.animal-query", name = "backend", havingValue = "elasticsearch", matchIfMissing = true)
@RequiredArgsConstructor
public class AnimalElasticsearchService implements AnimalQueryService {

    private static final int MAX_SEARCH_PAGE_SIZE = 100;
    private static final int MAX_RESULT_WINDOW = 10_000;

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
                .field("apms_desertion_no")
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
                .field("shelter_id")
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
            .must(Query.of(q -> q.term(t -> t.field("shelter_id").value(shelterId))))
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
            .must(Query.of(q -> q.term(t -> t.field("shelter_id").value(shelterId))))
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
                    .field("notice_end_date")
                    .gte(today.toString())
                    .lte(threeDaysLater.toString())
                )
            )))
        );

        // 공고 종료일 오름차순 정렬
        Pageable sortedPageable = PageRequest.of(
            pageable.getPageNumber(),
            pageable.getPageSize(),
            Sort.by(Sort.Direction.ASC, "notice_end_date")
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

        validateSearchPageable(pageable);

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
        List<Query> filterQueries = new ArrayList<>();

        // 1. 키워드 검색: 고유 특징과 구조화된 정보의 정확 일치를 우선하고 오타 검색은 보조로 사용한다.
        if (condition.getKeyword() != null && !condition.getKeyword().trim().isEmpty()) {
            mustQueries.add(createKeywordQuery(condition.getKeyword().trim()));
        }

        // 2. 공고번호는 분석·부분·오타 검색 없이 완전 일치만 허용한다.
        if (condition.getNoticeNo() != null && !condition.getNoticeNo().trim().isEmpty()) {
            filterQueries.add(Query.of(q -> q
                .term(t -> t
                    .field("apms_notice_no")
                    .value(condition.getNoticeNo().trim())
                )
            ));
        }

        // 3. 축종 필터
        if (condition.getSpecies() != null && !condition.getSpecies().trim().isEmpty()) {
            Query speciesQuery = Query.of(q -> q
                .term(t -> t
                    .field("species")
                    .value(condition.getSpecies().trim())
                )
            );
            filterQueries.add(speciesQuery);
        }

        // 4. 품종 검색: 정확 일치를 우선하고 오타 검색은 낮은 점수로 보조한다.
        if (condition.getBreed() != null && !condition.getBreed().trim().isEmpty()) {
            mustQueries.add(createBreedQuery(condition.getBreed().trim()));
        }

        // 5. 상태 필터. 공고번호 조회는 종료 상태도 찾을 수 있도록 기본 상태 제한을 적용하지 않는다.
        if (condition.getStatus() != null && !condition.getStatus().trim().isEmpty()) {
            Query statusQuery = Query.of(q -> q
                .term(t -> t
                    .field("status")
                    .value(condition.getStatus().trim())
                )
            );
            filterQueries.add(statusQuery);
        } else if (condition.getNoticeNo() == null || condition.getNoticeNo().isBlank()) {
            // 명시되지 않은 경우 입양 가능한 'NOTICE'(공고중), 'PROTECT'(보호중) 상태 기본 노출
            Query defaultStatusQuery = Query.of(q -> q
                .terms(t -> t
                    .field("status")
                    .terms(ts -> ts
                        .value(java.util.List.of(
                            co.elastic.clients.elasticsearch._types.FieldValue.of(AnimalStatus.NOTICE.name()),
                            co.elastic.clients.elasticsearch._types.FieldValue.of(AnimalStatus.PROTECT.name())
                        ))
                    )
                )
            );
            filterQueries.add(defaultStatusQuery);
        }

        // 6. 성별 필터
        if (condition.getGender() != null && !condition.getGender().trim().isEmpty()) {
            Query genderQuery = Query.of(q -> q
                .term(t -> t
                    .field("gender")
                    .value(condition.getGender().trim())
                )
            );
            filterQueries.add(genderQuery);
        }

        // 7. 중성화 필터
        if (condition.getNeuterStatus() != null && !condition.getNeuterStatus().trim().isEmpty()) {
            Query neuterQuery = Query.of(q -> q
                .term(t -> t
                    .field("neuter_status")
                    .value(condition.getNeuterStatus().trim())
                )
            );
            filterQueries.add(neuterQuery);
        }

        // 8. 보호소 ID 필터
        if (condition.getShelterId() != null) {
            Query shelterQuery = Query.of(q -> q
                .term(t -> t
                    .field("shelter_id")
                    .value(condition.getShelterId())
                )
            );
            filterQueries.add(shelterQuery);
        }

        // 9. 보호소 주소 검색 (지역 검색)
        if (condition.getShelterAddress() != null && !condition.getShelterAddress().trim().isEmpty()) {
            Query addressQuery = Query.of(q -> q
                .match(m -> m
                    .field("shelter_address")
                    .query(condition.getShelterAddress().trim())
                    .operator(co.elastic.clients.elasticsearch._types.query_dsl.Operator.And)
                )
            );
            filterQueries.add(addressQuery);
        }

        // 10. 나이 범위 (출생 연도 기준)
        if (condition.getMinBirthYear() != null && condition.getMaxBirthYear() != null) {
            // 최소/최대 둘 다 있는 경우
            Query ageRangeQuery = Query.of(q -> q
                .range(r -> r
                    .number(n -> n
                        .field("birth_year")
                        .gte(condition.getMinBirthYear().doubleValue())
                        .lte(condition.getMaxBirthYear().doubleValue())
                    )
                )
            );
            filterQueries.add(ageRangeQuery);
        } else if (condition.getMinBirthYear() != null) {
            // 최소만 있는 경우
            Query ageRangeQuery = Query.of(q -> q
                .range(r -> r
                    .number(n -> n
                        .field("birth_year")
                        .gte(condition.getMinBirthYear().doubleValue())
                    )
                )
            );
            filterQueries.add(ageRangeQuery);
        } else if (condition.getMaxBirthYear() != null) {
            // 최대만 있는 경우
            Query ageRangeQuery = Query.of(q -> q
                .range(r -> r
                    .number(n -> n
                        .field("birth_year")
                        .lte(condition.getMaxBirthYear().doubleValue())
                    )
                )
            );
            filterQueries.add(ageRangeQuery);
        }

        // Bool Query에 조건 추가
        if (!mustQueries.isEmpty()) {
            boolQuery.must(mustQueries);
        }
        if (!filterQueries.isEmpty()) {
            boolQuery.filter(filterQueries);
        }

        // Pageable 생성 (페이징 및 정렬)
        Pageable pageable = createPageable(condition);

        // NativeQuery 생성
        var nativeQueryBuilder = NativeQuery.builder()
            .withQuery(Query.of(q -> q.bool(boolQuery.build())))
            .withPageable(pageable)
            .withTrackTotalHits(true); // 10,000건 이상 정확한 카운트 반환

        if ("relevance".equals(condition.getSortBy())) {
            nativeQueryBuilder
                .withSort(s -> s.score(score -> score.order(SortOrder.Desc)))
                .withSort(s -> s.field(field -> field.field("created_at").order(SortOrder.Desc)))
                .withSort(s -> s.field(field -> field.field("id").order(SortOrder.Asc)));
        }

        NativeQuery nativeQuery = nativeQueryBuilder.build();

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
                .field("shelter_id")
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

    private Query createKeywordQuery(String keyword) {
        List<Query> evidenceQueries = List.of(
            Query.of(q -> q.matchPhrase(m -> m.field("special_mark").query(keyword).boost(9.0f))),
            Query.of(q -> q.matchPhrase(m -> m.field("breed").query(keyword).boost(8.0f))),
            Query.of(q -> q.matchPhrase(m -> m.field("color").query(keyword).boost(7.0f))),
            Query.of(q -> q.matchPhrase(m -> m.field("description").query(keyword).boost(7.0f))),
            Query.of(q -> q.matchPhrase(m -> m.field("happen_place").query(keyword).boost(4.0f))),
            Query.of(q -> q.matchPhrase(m -> m.field("shelter_name").query(keyword).boost(2.0f))),
            Query.of(q -> q.multiMatch(m -> m
                .query(keyword)
                .type(TextQueryType.MostFields)
                .fields(
                    "special_mark^6",
                    "breed^6",
                    "color^5",
                    "description^4",
                    "happen_place^4",
                    "shelter_name^2",
                    "shelter_address^1.5"
                )
                .minimumShouldMatch("70%")
            )),
            Query.of(q -> q.multiMatch(m -> m
                .query(keyword)
                .type(TextQueryType.MostFields)
                .fields("breed^5", "color^4", "special_mark^3", "shelter_name")
                .fuzziness("AUTO")
                .minimumShouldMatch("70%")
                .boost(0.5f)
            ))
        );

        return Query.of(q -> q.bool(b -> b
            .should(evidenceQueries)
            .minimumShouldMatch("1")
        ));
    }

    private Query createBreedQuery(String breed) {
        return Query.of(q -> q.bool(b -> b
            .should(
                Query.of(e -> e.term(t -> t.field("breed.keyword").value(breed).boost(10.0f))),
                Query.of(e -> e.matchPhrase(m -> m.field("breed").query(breed).boost(7.0f))),
                Query.of(e -> e.match(m -> m
                    .field("breed")
                    .query(breed)
                    .operator(Operator.And)
                    .fuzziness("AUTO")
                    .boost(0.5f)
                ))
            )
            .minimumShouldMatch("1")
        ));
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

        String requestedSort = condition.getSortBy();
        if ("relevance".equals(requestedSort)) {
            boolean hasKeyword = condition.getKeyword() != null && !condition.getKeyword().isBlank();
            boolean hasBreed = condition.getBreed() != null && !condition.getBreed().isBlank();
            if (!hasKeyword && !hasBreed) {
                throw new IllegalArgumentException("관련도순 정렬에는 키워드나 품종이 필요합니다.");
            }
            return PageRequest.of(condition.getPage(), condition.getSize());
        }

        String sortBy = switch (requestedSort) {
            case "createdAt" -> "created_at";
            case "updatedAt" -> "updated_at";
            case "noticeEndDate" -> "notice_end_date";
            case "birthYear" -> "birth_year";
            case "age" -> "birth_year";
            case "apmsDesertionNo" -> "apms_desertion_no";
            case "favoriteCount" -> "favorite_count";
            default -> throw new IllegalArgumentException("지원하지 않는 정렬 기준입니다: " + requestedSort);
        };

        // 나이가 적은 순서는 출생 연도가 최근인 순서다.
        if ("age".equals(requestedSort)) {
            direction = direction.isAscending() ? Sort.Direction.DESC : Sort.Direction.ASC;
        }

        Sort sort = Sort.by(
            new Sort.Order(direction, sortBy),
            new Sort.Order(Sort.Direction.ASC, "id")
        );

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

        // 공고번호 (완전 일치)
        if (request.getNoticeNo() != null && !request.getNoticeNo().trim().isEmpty()) {
            builder.noticeNo(request.getNoticeNo().trim());
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

        builder.shelterId(request.getShelterId());

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

    private void validateSearchPageable(Pageable pageable) {
        if (pageable.getPageSize() < 1 || pageable.getPageSize() > MAX_SEARCH_PAGE_SIZE) {
            throw new IllegalArgumentException("페이지 크기는 1 이상 100 이하여야 합니다.");
        }
        if (pageable.getSort().stream().count() > 1) {
            throw new IllegalArgumentException("정렬 기준은 하나만 지정할 수 있습니다.");
        }

        long lastResultExclusive = pageable.getOffset() + pageable.getPageSize();
        if (lastResultExclusive > MAX_RESULT_WINDOW) {
            throw new IllegalArgumentException("검색 결과는 최대 10,000건까지만 조회할 수 있습니다. 검색 조건을 좁혀주세요.");
        }
    }
}
