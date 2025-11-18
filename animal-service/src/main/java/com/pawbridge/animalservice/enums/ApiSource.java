package com.pawbridge.animalservice.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum ApiSource {
    APMS_ANIMAL("APMS 구조동물 API"),
    GYEONGGI("경기도 유기동물 API"),
    MANUAL("수동 입력"),
    UNKNOWN("출처 미상");

    private final String description;

    public static ApiSource fromCode(String code) {
        if (code == null) {
            return UNKNOWN;
        }

        for (ApiSource apiSource : ApiSource.values()) {
            if (apiSource.getDescription().equals(code)) {
                return apiSource;
            }
        }

        return UNKNOWN;
    }
}
