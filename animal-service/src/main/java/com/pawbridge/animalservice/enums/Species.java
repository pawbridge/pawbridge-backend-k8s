package com.pawbridge.animalservice.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum Species {
    DOG("417000", "개"),
    CAT("422400", "고양이"),
    ETC("429900", "기타");

    private final String code;
    private final String description;

    public static Species fromCode(String code) {
        if (code == null) {
            return ETC;
        }

        for (Species species : Species.values()) {
            if (species.getCode().equals(code)) {
                return species;
            }
        }

        return ETC;
    }
}
