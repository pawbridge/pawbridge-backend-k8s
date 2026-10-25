package com.pawbridge.backend.user.internal.password.application

import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import org.springframework.transaction.annotation.Transactional

// Explicitly open: the method-level @Transactional is proxied by CGLIB, and kotlin-spring only opens
// classes that carry a class-level Spring stereotype or @Transactional.
open class ChangeUserPassword(
    private val users: UserPasswordStore,
    private val hashes: PasswordHashes,
) {

    @Transactional(transactionManager = "writeTransactionManager", timeout = 10)
    open fun change(userId: Long, currentPassword: String?, newPassword: String?) {
        val credentials = users.findCredentials(userId).orElseThrow { UserNotFoundException() }
        if (LOCAL_PROVIDER != credentials.provider) {
            throw OAuthUserCannotChangePasswordException()
        }
        if (!hashes.matches(currentPassword, credentials.password)) {
            throw InvalidCurrentPasswordException()
        }
        // The legacy service compares the raw inputs only after the current password matched.
        // `!!` keeps the NullPointerException that `currentPassword.equals(newPassword)` threw for a null input.
        if (currentPassword!! == newPassword) {
            throw SamePasswordException()
        }
        users.updatePassword(userId, hashes.encode(newPassword))
    }

    private companion object {
        private const val LOCAL_PROVIDER = "LOCAL"
    }
}
