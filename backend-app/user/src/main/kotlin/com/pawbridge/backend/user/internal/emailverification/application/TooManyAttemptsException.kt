package com.pawbridge.backend.user.internal.emailverification.application

class TooManyAttemptsException(private val kind: Kind) : RuntimeException() {

    enum class Kind { SEND, VERIFY }

    // Java callers and the exception handler use the record-style accessor name.
    fun kind(): Kind = kind
}
