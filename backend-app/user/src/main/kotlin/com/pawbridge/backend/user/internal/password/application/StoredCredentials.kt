package com.pawbridge.backend.user.internal.password.application

@JvmRecord
data class StoredCredentials(
    val provider: String?,
    val password: String?,
) {
    override fun toString(): String {
        // Never expose the stored hash through logs or exception messages.
        return "StoredCredentials[provider=$provider, password=<redacted>]"
    }
}
