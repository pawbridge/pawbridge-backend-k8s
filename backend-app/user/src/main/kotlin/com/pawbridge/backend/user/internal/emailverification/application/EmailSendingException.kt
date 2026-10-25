package com.pawbridge.backend.user.internal.emailverification.application

class EmailSendingException : RuntimeException {
    constructor() : super()

    // The Java version passed a null message, so the cause's toString() never becomes the message.
    constructor(cause: Throwable?) : super(null, cause)
}
