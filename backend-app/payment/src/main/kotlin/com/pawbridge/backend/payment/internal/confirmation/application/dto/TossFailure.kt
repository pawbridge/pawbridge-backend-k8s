package com.pawbridge.backend.payment.internal.confirmation.application.dto

class TossFailure {
    var code: String? = null
        private set

    var message: String? = null
        private set

    override fun toString(): String = "TossFailure(code=$code, message=$message)"
}
