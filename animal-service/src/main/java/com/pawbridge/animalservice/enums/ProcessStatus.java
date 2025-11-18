package com.pawbridge.animalservice.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * RawApmsData 처리 상태
 * - APMS API 응답의 파싱 처리 상태를 추적
 */
@RequiredArgsConstructor
@Getter
public enum ProcessStatus {
    PENDING("처리 대기"),      // 수집 완료, 파싱 전
    PROCESSED("처리 완료"),    // 파싱 성공, Animal 엔티티 저장 완료
    FAILED("처리 실패");       // 파싱 실패 (errorMessage 참고)

    private final String description;
}
