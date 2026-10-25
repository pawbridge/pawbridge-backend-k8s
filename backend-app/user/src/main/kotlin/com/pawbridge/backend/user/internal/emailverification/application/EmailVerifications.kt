package com.pawbridge.backend.user.internal.emailverification.application

import java.security.SecureRandom

/** Original EmailVerificationService: 6-digit codes, 5-minute TTL, 5 sends and 5 failed verifications per window. */
// Open like the Java class: it was not final, so it can still be subclassed or mocked.
// The e-mail parameters stay nullable because the Java version only used them to build keys and mail recipients.
open class EmailVerifications(
    private val store: VerificationStore,
    private val mails: VerificationMails,
) {

    open fun sendVerificationCode(email: String?) {
        send(SIGNUP, email) { code -> mails.sendVerification(email, code) }
    }

    open fun verifyCode(email: String?, code: String?): Boolean = verify(SIGNUP, email, code)

    open fun isVerified(email: String?): Boolean = "true" == store.get(SIGNUP.verified + email)

    open fun clearVerification(email: String?) {
        store.delete(SIGNUP.verified + email)
    }

    open fun sendPasswordResetCode(email: String?) {
        send(RESET, email) { code -> mails.sendPasswordReset(email, code) }
    }

    open fun verifyPasswordResetCode(email: String?, code: String?): Boolean = verify(RESET, email, code)

    open fun clearPasswordResetVerification(email: String?) {
        store.delete(RESET.verified + email)
    }

    private fun send(keys: Keys, email: String?, mail: (String) -> Unit) {
        // 1. Atomic send-count check, 2. code, 3. mail first (a failure stores nothing), 4. store, 5. reset attempts.
        if (!store.tryIncrementSendCount(keys.sendCount + email, MAX_SEND_ATTEMPTS, CODE_EXPIRATION_SECONDS)) {
            throw TooManyAttemptsException(TooManyAttemptsException.Kind.SEND)
        }
        val code = String.format("%0" + CODE_LENGTH + "d", RANDOM.nextInt(Math.pow(10.0, CODE_LENGTH.toDouble()).toInt()))
        mail(code)
        store.put(keys.code + email, code, CODE_EXPIRATION_SECONDS)
        store.delete(keys.attempts + email)
    }

    private fun verify(keys: Keys, email: String?, code: String?): Boolean {
        val attempts = store.get(keys.attempts + email)
        if (attempts != null && Integer.parseInt(attempts) >= MAX_VERIFY_ATTEMPTS) {
            throw TooManyAttemptsException(TooManyAttemptsException.Kind.VERIFY)
        }
        val stored = store.get(keys.code + email) ?: throw ExpiredCodeException()
        if (stored != code) {
            store.incrementWithExpire(keys.attempts + email, CODE_EXPIRATION_SECONDS)
            throw InvalidCodeException()
        }
        store.put(keys.verified + email, "true", VERIFIED_EXPIRATION_SECONDS)
        store.delete(keys.code + email)
        store.delete(keys.attempts + email)
        return true
    }

    private class Keys(
        val code: String,
        val attempts: String,
        val verified: String,
        val sendCount: String,
    )

    private companion object {
        private const val CODE_LENGTH = 6
        private const val CODE_EXPIRATION_SECONDS = 5L * 60
        private const val VERIFIED_EXPIRATION_SECONDS = 60L * 60
        private const val MAX_SEND_ATTEMPTS = 5
        private const val MAX_VERIFY_ATTEMPTS = 5
        private val RANDOM = SecureRandom()

        private val SIGNUP = Keys("email:code:", "email:attempts:", "email:verified:", "email:send:count:")
        private val RESET = Keys(
            "password:reset:code:",
            "password:reset:attempts:",
            "password:reset:verified:",
            "password:reset:send:count:",
        )
    }
}
