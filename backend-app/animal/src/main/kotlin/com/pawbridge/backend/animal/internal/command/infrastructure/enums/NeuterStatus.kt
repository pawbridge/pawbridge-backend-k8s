package com.pawbridge.backend.animal.internal.command.infrastructure.enums

enum class NeuterStatus(val code: String, val description: String) {
    YES("Y", "중성화"),
    NO("N", "비중성화"),
    UNKNOWN("U", "미상"),
    ;

    companion object {
        @JvmStatic
        fun fromCode(code: String?): NeuterStatus {
            if (code == null) {
                return UNKNOWN
            }

            for (neuterStatus in entries) {
                if (neuterStatus.code == code) {
                    return neuterStatus
                }
            }

            return UNKNOWN
        }
    }
}
