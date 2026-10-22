package com.pawbridge.backend.user.internal.emailverification.application;

public interface VerificationMails {
    void sendVerification(String to, String code);

    void sendPasswordReset(String to, String code);
}
