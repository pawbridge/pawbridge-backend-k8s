package com.pawbridge.backend.animal.internal.command.infrastructure.enums

enum class Gender(val code: String, val description: String) {
    MALE("M", "수컷"),
    FEMALE("F", "암컷"),
    UNKNOWN("Q", "미상"),
    ;

    companion object {
        @JvmStatic
        fun fromCode(code: String?): Gender {
            if (code == null) {
                return UNKNOWN
            }

            for (gender in entries) {
                if (gender.code == code) {
                    return gender
                }
            }

            return UNKNOWN
        }
    }
}
