package com.pawbridge.animalservice.repository;

import com.pawbridge.animalservice.document.AnimalDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Elasticsearch AnimalDocument Repository
 * - Spring Data Elasticsearch의 기본 CRUD 제공
 * - 커스텀 검색 쿼리 메서드
 */
@Repository
public interface AnimalDocumentRepository extends ElasticsearchRepository<AnimalDocument, String> {

    /**
     * 축종으로 검색
     * @param species 축종
     * @return 동물 리스트
     */
    List<AnimalDocument> findBySpecies(String species);

    /**
     * 상태로 검색
     * @param status 상태
     * @return 동물 리스트
     */
    List<AnimalDocument> findByStatus(String status);

    /**
     * 축종과 상태로 검색
     * @param species 축종
     * @param status 상태
     * @return 동물 리스트
     */
    List<AnimalDocument> findBySpeciesAndStatus(String species, String status);

    /**
     * 공고 종료일 기준 검색 (공고 종료 임박)
     * @param startDate 시작일
     * @param endDate 종료일
     * @return 동물 리스트
     */
    List<AnimalDocument> findByNoticeEndDateBetween(LocalDate startDate, LocalDate endDate);

    /**
     * 보호소 ID로 검색
     * @param shelterId 보호소 ID
     * @return 동물 리스트
     */
    List<AnimalDocument> findByShelterId(Long shelterId);
}
