package com.pawbridge.backend.user.internal.signup.application

import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications
import com.pawbridge.backend.user.internal.password.application.PasswordHashes
import org.springframework.transaction.annotation.Transactional

/** Original UserServiceImpl.signUp: verified e-mail, duplicate LOCAL e-mail, password match, USER-only roles. */
// Explicitly open: the method-level @Transactional is proxied by CGLIB, and kotlin-spring only opens
// classes that carry a class-level Spring stereotype or @Transactional.
open class LocalSignup(
    private val verifications: EmailVerifications,
    private val accounts: SignupAccounts,
    private val hashes: PasswordHashes,
) {

    @Transactional(transactionManager = "writeTransactionManager")
    open fun signUp(
        email: String?,
        name: String?,
        password: String?,
        rePassword: String?,
        role: SignupRole?,
        careRegNo: String?,
    ): SignedUp {
        if (!verifications.isVerified(email)) {
            throw EmailNotVerifiedException()
        }
        if (accounts.existsLocalEmail(email)) {
            throw EmailDuplicateException()
        }
        // `!!` keeps the NullPointerException that `password.equals(rePassword)` threw for a null password.
        if (password!! != rePassword) {
            throw InconsistentPasswordException()
        }
        if (role == null) {
            throw RoleRequiredException()
        }
        if (role == SignupRole.ROLE_ADMIN) {
            throw AdminRoleNotAllowedException()
        }
        // Shelter rights are granted only through the post-login application and administrator approval.
        if (role != SignupRole.ROLE_USER || (careRegNo != null && !isBlank(careRegNo))) {
            throw ShelterApprovalRequiredException()
        }
        val saved = accounts.createLocalUser(email, name, hashes.encode(password), role, careRegNo)
        try {
            verifications.clearVerification(email)
        } catch (ignored: RuntimeException) {
            // The original logs and ignores a failed cleanup.
        }
        return saved
    }

    private companion object {
        // Java String.isBlank semantics (Character.isWhitespace): Kotlin's isBlank would also treat NBSP,
        // U+2007 and U+202F as blank, so a care registration number made of those would no longer be rejected.
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }
    }
}
