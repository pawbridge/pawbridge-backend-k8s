package com.pawbridge.backend.animal.internal.command.infrastructure.enums

enum class AnimalStatus(val description: String) {
    NOTICE("공고중"),
    PROTECT("보호중"),
    ADOPTION_PENDING("입양대기중"), // 입양 절차 진행 중
    ADOPTED("종료(입양)"),
    EUTHANIZED("종료(안락사)"),
    NATURAL_DEATH("종료(자연사)"),
    RETURNED("종료(반환)"), // 주인에게 반환
    DONATED("종료(기증)"), // 다른 기관에 기증
    RELEASED("종료(방사)"), // 야생 방사
    ESCAPED("탈출"), // 탈출
    UNKNOWN("미상"),
    ;

    companion object {
        /**
         * APMS processState 값으로 AnimalStatus 변환
         *
         * @param code APMS processState (예: "보호중", "종료(입양)" 등)
         * @return AnimalStatus
         */
        @JvmStatic
        fun fromCode(code: String?): AnimalStatus {
            // Java String.isBlank() uses Character.isWhitespace; Kotlin isBlank() also accepts NBSP.
            if (code == null || code.all { Character.isWhitespace(it) }) {
                return UNKNOWN
            }

            // 정확히 일치하는 값 찾기 우선
            // Java String.trim() removes every character up to U+0020; Kotlin trim() uses isWhitespace.
            val trimmed = code.trim { it <= ' ' }
            for (status in entries) {
                if (status.description == trimmed) {
                    return status
                }
            }

            // 조금 다를 수 있는 포맷 방어를 위해 contains 처리
            if (code.contains("보호")) return PROTECT
            if (code.contains("공고")) return NOTICE
            if (code.contains("입양") && !code.contains("대기")) return ADOPTED
            if (code.contains("반환")) return RETURNED
            if (code.contains("자연사")) return NATURAL_DEATH
            if (code.contains("안락사")) return EUTHANIZED
            if (code.contains("기증")) return DONATED
            if (code.contains("방사")) return RELEASED
            if (code.contains("탈출")) return ESCAPED

            // 매핑 안 되면 UNKNOWN
            return UNKNOWN
        }
    }
}
