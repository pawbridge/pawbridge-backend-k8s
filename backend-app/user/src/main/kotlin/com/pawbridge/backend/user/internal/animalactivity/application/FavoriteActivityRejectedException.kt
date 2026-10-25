package com.pawbridge.backend.user.internal.animalactivity.application

class FavoriteActivityRejectedException(private val reason: Reason) : RuntimeException(reason.message()) {

    enum class Reason(
        private val status: Int,
        private val message: String,
    ) {
        ALREADY_EXISTS(409, "이미 찜한 동물입니다."),
        NOT_FOUND(404, "찜 내역을 찾을 수 없습니다."),
        ANIMAL_NOT_FOUND(404, "동물을 찾을 수 없습니다"),
        ;

        // Java callers and the exception handler use the record-style accessor names.
        fun status(): Int = status

        fun message(): String = message
    }

    fun reason(): Reason = reason
}
