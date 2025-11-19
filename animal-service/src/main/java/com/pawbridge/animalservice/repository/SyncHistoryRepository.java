package com.pawbridge.animalservice.repository;

import com.pawbridge.animalservice.entity.SyncHistory;
import com.pawbridge.animalservice.enums.ApiSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * SyncHistory Repository
 */
public interface SyncHistoryRepository extends JpaRepository<SyncHistory, Long> {

    /**
     * API 출처별 최근 동기화 이력 조회 (최신순)
     *
     * @param apiSource API 출처
     * @return 동기화 이력 목록
     */
    List<SyncHistory> findByApiSourceOrderByStartTimeDesc(ApiSource apiSource);

    /**
     * API 출처별 가장 최근 동기화 이력 조회
     *
     * @param apiSource API 출처
     * @return 최근 동기화 이력
     */
    Optional<SyncHistory> findFirstByApiSourceOrderByStartTimeDesc(ApiSource apiSource);
}
