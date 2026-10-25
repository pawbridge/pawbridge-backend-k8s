package com.pawbridge.backend.user.api

class UserNicknameUnavailableException(cause: Throwable?) :
    RuntimeException("User nickname lookup is unavailable.", cause)
