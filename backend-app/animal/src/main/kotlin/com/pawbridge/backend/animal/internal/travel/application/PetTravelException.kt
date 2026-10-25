package com.pawbridge.backend.animal.internal.travel.application

class PetTravelException(val code: Code) : RuntimeException(code.name) {

    enum class Code { INVALID_REQUEST, NOT_FOUND, UNAVAILABLE }

    companion object {
        @JvmStatic
        fun unavailable(): PetTravelException {
            return PetTravelException(Code.UNAVAILABLE)
        }
    }
}
