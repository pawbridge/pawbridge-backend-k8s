package com.pawbridge.backend.user.internal.passwordreset.application;

import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications;
import com.pawbridge.backend.user.internal.password.application.PasswordHashes;
import java.util.OptionalLong;
import org.springframework.transaction.annotation.Transactional;

/** Original AuthServiceImpl reset flow: mail only for LOCAL accounts, identical response either way. */
public class PasswordReset {
    private final PasswordResetAccounts accounts;
    private final EmailVerifications verifications;
    private final PasswordHashes hashes;

    public PasswordReset(PasswordResetAccounts accounts, EmailVerifications verifications, PasswordHashes hashes) {
        this.accounts = accounts;
        this.verifications = verifications;
        this.hashes = hashes;
    }

    @Transactional(transactionManager = "writeTransactionManager", readOnly = true)
    public void request(String email) {
        if (accounts.findLocalUserId(email).isPresent()) {
            try {
                verifications.sendPasswordResetCode(email);
            } catch (Exception ignored) {
                // Failures are hidden so account existence is not exposed.
            }
        }
    }

    @Transactional(transactionManager = "writeTransactionManager")
    public void reset(String email, String code, String newPassword) {
        OptionalLong userId = accounts.findLocalUserId(email);
        if (userId.isEmpty()) {
            throw new PasswordResetCodeInvalidException();
        }
        try {
            if (!verifications.verifyPasswordResetCode(email, code)) {
                throw new PasswordResetCodeInvalidException();
            }
        } catch (Exception failure) {
            throw new PasswordResetCodeInvalidException();
        }
        accounts.updatePassword(userId.getAsLong(), hashes.encode(newPassword));
        try {
            verifications.clearPasswordResetVerification(email);
        } catch (Exception ignored) {
            // The original logs and ignores a failed cleanup.
        }
    }
}
