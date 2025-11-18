package com.pawbridge.animalservice.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum Gender {
    MALE("M", "수컷"),
    FEMALE("F", "암컷"),
    UNKNOWN("Q", "미상");

    private final String code;
    private final String description;

    public static Gender fromCode(String code) {
        if (code == null) {
            return UNKNOWN;
        }

        for (Gender gender : Gender.values()) {
            if (gender.getCode().equals(code)) {
                return gender;
            }
        }

        return UNKNOWN;
    }
}
