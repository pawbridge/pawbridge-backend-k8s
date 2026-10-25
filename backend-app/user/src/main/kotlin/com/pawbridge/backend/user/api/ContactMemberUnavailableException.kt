package com.pawbridge.backend.user.api

class ContactMemberUnavailableException(cause: Throwable?) :
    RuntimeException("Contact member lookup is unavailable.", cause)
