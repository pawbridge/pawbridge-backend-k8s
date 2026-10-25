package com.pawbridge.backend.user.internal.session.application

class AuthSessionRejectedException(private val reason: Reason?) : RuntimeException(message(reason)) {

    enum class Reason {
        /** No refresh cookie was sent. */
        MISSING,

        /** Unknown, expired or revoked token. */
        INVALID,

        /** A token that was already rotated away was presented again; its session has been revoked. */
        REUSED,
    }

    fun reason(): Reason? = reason

    private companion object {
        // Reuse gets the same message as an invalid token so the response does not confirm a stolen token.
        private fun message(reason: Reason?): String {
            if (reason == Reason.MISSING) {
                return "리프레시 토큰을 찾을 수 없습니다."
            }
            return "유효하지 않은 토큰입니다."
        }
    }
}
