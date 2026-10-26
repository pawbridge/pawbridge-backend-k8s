package com.pawbridge.backend.user.internal.passwordreset.application

import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications
import com.pawbridge.backend.user.internal.password.application.PasswordHashes
import org.springframework.transaction.annotation.Transactional

/** Original AuthServiceImpl reset flow: mail only for LOCAL accounts, identical response either way. */
// Explicitly open: the method-level @Transactional is proxied by CGLIB, and kotlin-spring only opens
// classes that carry a class-level Spring stereotype or @Transactional.
open class PasswordReset(
    private val accounts: PasswordResetAccounts,
    private val verifications: EmailVerifications,
    private val hashes: PasswordHashes,
) {

    @Transactional(transactionManager = "writeTransactionManager", readOnly = true)
    open fun request(email: String?) {
        if (accounts.findLocalUserId(email).isPresent) {
            try {
                verifications.sendPasswordResetCode(email)
            } catch (ignored: Exception) {
                // Failures are hidden so account existence is not exposed.
            }
        }
    }

    @Transactional(transactionManager = "writeTransactionManager")
    open fun reset(email: String?, code: String?, newPassword: String?) {
        val userId = accounts.findLocalUserId(email)
        if (userId.isEmpty) {
            throw PasswordResetCodeInvalidException()
        }
        try {
            if (!verifications.verifyPasswordResetCode(email, code)) {
                throw PasswordResetCodeInvalidException()
            }
        } catch (failure: Exception) {
            throw PasswordResetCodeInvalidException()
        }
        accounts.updatePassword(userId.asLong, hashes.encode(newPassword))
        try {
            verifications.clearPasswordResetVerification(email)
        } catch (ignored: Exception) {
            // The original logs and ignores a failed cleanup.
        }
    }
}
