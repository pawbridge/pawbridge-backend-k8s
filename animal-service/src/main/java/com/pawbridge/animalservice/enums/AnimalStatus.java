package com.pawbridge.animalservice.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum AnimalStatus {
    NOTICE("공고중"),
    PROTECT("보호중"),
    ADOPTION_PENDING("입양대기중"),    //입양 절차 진행 중
    ADOPTED("입양완료"),
    EUTHANIZED("안락사"),
    NATURAL_DEATH("자연사"),
    RETURNED("반환"),                 // 주인에게 반환
    ESCAPED("탈출"),                  // 탈출
    UNKNOWN("미상");

    private final String description;

    public static AnimalStatus fromCode(String code) {
        if (code == null) {
            return NOTICE;
        }

        for (AnimalStatus animalStatus : AnimalStatus.values()) {
            if (animalStatus.getDescription().equals(code)) {
                return animalStatus;
            }
        }

        return UNKNOWN;
    }
}
