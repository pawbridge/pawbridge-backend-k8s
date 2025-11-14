package com.pawbridge.animalservice.entity;

import com.pawbridge.animalservice.enums.ApiSource;
import com.pawbridge.animalservice.enums.SyncStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 배치 실행 이력
 * - APMS API 동기화 이력을 추적
 * - Spring Batch JobRepository와는 별개로 비즈니스 레벨 추적
 */
@Entity
@Table(name = "sync_history")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncHistory {

    /**
     * PK
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * API 출처
     * - APMS_ANIMAL, GYEONGGI 등
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApiSource apiSource;

    /**
     * 동기화 상태
     * - IN_PROGRESS, SUCCESS, FAIL, PARTIAL_SUCCESS
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncStatus syncStatus;

    /**
     * 시작 시각
     */
    @Column(nullable = false)
    private LocalDateTime startTime;

    /**
     * 종료 시각
     */
    @Column
    private LocalDateTime endTime;

    /**
     * 총 처리 대상 수
     * - API 응답의 totalCount
     */
    @Column
    private Integer totalCount;

    /**
     * 성공 건수
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer successCount = 0;

    /**
     * 실패 건수
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer failCount = 0;

    /**
     * 에러 메시지
     * - 실패 시 원인 추적용
     */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * 동기화 완료 처리
     */
    public void complete(SyncStatus status, Integer successCount, Integer failCount) {
        this.syncStatus = status;
        this.endTime = LocalDateTime.now();
        this.successCount = successCount;
        this.failCount = failCount;
    }

    /**
     * 에러 메시지 설정
     */
    public void setError(String errorMessage) {
        this.errorMessage = errorMessage;
        this.syncStatus = SyncStatus.FAIL;
        this.endTime = LocalDateTime.now();
    }
}
