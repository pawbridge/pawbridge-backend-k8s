package com.pawbridge.backend.animal.internal.command.infrastructure.enums

enum class Species(val code: String, val description: String) {
    DOG("417000", "개"),
    CAT("422400", "고양이"),
    ETC("429900", "기타"),
    ;

    companion object {
        @JvmStatic
        fun fromCode(code: String?): Species {
            if (code == null) {
                return ETC
            }

            for (species in entries) {
                if (species.code == code) {
                    return species
                }
            }

            return ETC
        }
    }
}
