package com.pawbridge.backend.animal.internal.command.infrastructure.enums

enum class ApiSource(val description: String) {
    APMS_ANIMAL("APMS 구조동물 API"),
    GYEONGGI("경기도 유기동물 API"),
    MANUAL("수동 입력"),
    UNKNOWN("출처 미상"),
    ;

    companion object {
        @JvmStatic
        fun fromCode(code: String?): ApiSource {
            if (code == null) {
                return UNKNOWN
            }

            for (apiSource in entries) {
                if (apiSource.description == code) {
                    return apiSource
                }
            }

            return UNKNOWN
        }
    }
}
