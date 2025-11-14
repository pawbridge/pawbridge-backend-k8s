package com.pawbridge.animalservice.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 배치 동기화 상태
 */
@Getter
@RequiredArgsConstructor
public enum SyncStatus {

    IN_PROGRESS("진행 중"),
    SUCCESS("성공"),
    FAIL("실패"),
    PARTIAL_SUCCESS("부분 성공");

    private final String description;
}
