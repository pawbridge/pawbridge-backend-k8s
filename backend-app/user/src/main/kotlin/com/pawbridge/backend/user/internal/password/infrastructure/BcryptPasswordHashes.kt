package com.pawbridge.backend.user.internal.password.infrastructure

import com.pawbridge.backend.user.internal.password.application.PasswordHashes
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

class BcryptPasswordHashes : PasswordHashes {
    // The legacy user-service registered a default-strength BCryptPasswordEncoder.
    private val encoder: PasswordEncoder = BCryptPasswordEncoder()

    override fun matches(rawPassword: String?, encodedPassword: String?): Boolean =
        encoder.matches(rawPassword, encodedPassword)

    override fun encode(rawPassword: String?): String? = encoder.encode(rawPassword)
}
