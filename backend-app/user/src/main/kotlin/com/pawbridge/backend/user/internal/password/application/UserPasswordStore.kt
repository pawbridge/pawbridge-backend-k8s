package com.pawbridge.backend.user.internal.password.application

import java.util.Optional

interface UserPasswordStore {
    fun findCredentials(userId: Long): Optional<StoredCredentials>

    fun updatePassword(userId: Long, encodedPassword: String?)
}
