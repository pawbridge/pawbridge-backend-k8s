package com.pawbridge.backend.user.internal.signup.application;

import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications;
import com.pawbridge.backend.user.internal.password.application.PasswordHashes;
import org.springframework.transaction.annotation.Transactional;

/** Original UserServiceImpl.signUp: verified e-mail, duplicate LOCAL e-mail, password match, USER-only roles. */
public class LocalSignup {
    private final EmailVerifications verifications;
    private final SignupAccounts accounts;
    private final PasswordHashes hashes;

    public LocalSignup(EmailVerifications verifications, SignupAccounts accounts, PasswordHashes hashes) {
        this.verifications = verifications;
        this.accounts = accounts;
        this.hashes = hashes;
    }

    @Transactional(transactionManager = "writeTransactionManager")
    public SignedUp signUp(String email, String name, String password, String rePassword, SignupRole role,
            String careRegNo) {
        if (!verifications.isVerified(email)) {
            throw new EmailNotVerifiedException();
        }
        if (accounts.existsLocalEmail(email)) {
            throw new EmailDuplicateException();
        }
        if (!password.equals(rePassword)) {
            throw new InconsistentPasswordException();
        }
        if (role == null) {
            throw new RoleRequiredException();
        }
        if (role == SignupRole.ROLE_ADMIN) {
            throw new AdminRoleNotAllowedException();
        }
        // Shelter rights are granted only through the post-login application and administrator approval.
        if (role != SignupRole.ROLE_USER || (careRegNo != null && !careRegNo.isBlank())) {
            throw new ShelterApprovalRequiredException();
        }
        SignedUp saved = accounts.createLocalUser(email, name, hashes.encode(password), role, careRegNo);
        try {
            verifications.clearVerification(email);
        } catch (RuntimeException ignored) {
            // The original logs and ignores a failed cleanup.
        }
        return saved;
    }
}
