package com.pawbridge.animalservice.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum NeuterStatus {
    YES("Y", "중성화"),
    NO("N", "비중성화"),
    UNKNOWN("U", "미상");

    private final String code;
    private final String description;

    public static NeuterStatus fromCode(String code) {
        if (code == null) {
            return UNKNOWN;
        }

        for (NeuterStatus neuterStatus : NeuterStatus.values()) {
            if (neuterStatus.getCode().equals(code)) {
                return neuterStatus;
            }
        }

        return UNKNOWN;
    }
}
