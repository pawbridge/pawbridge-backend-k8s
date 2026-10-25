package com.pawbridge.backend.user.internal.passwordreset.application

import java.util.OptionalLong

interface PasswordResetAccounts {
    fun findLocalUserId(email: String?): OptionalLong

    fun updatePassword(userId: Long, encodedPassword: String?)
}
