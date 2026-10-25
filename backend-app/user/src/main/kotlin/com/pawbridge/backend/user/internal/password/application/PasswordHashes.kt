package com.pawbridge.backend.user.internal.password.application

interface PasswordHashes {
    fun matches(rawPassword: String?, encodedPassword: String?): Boolean

    fun encode(rawPassword: String?): String?
}
