package com.pawbridge.backend.user.internal.emailverification.application;

import java.security.SecureRandom;

/** Original EmailVerificationService: 6-digit codes, 5-minute TTL, 5 sends and 5 failed verifications per window. */
public class EmailVerifications {
    private static final int CODE_LENGTH = 6;
    private static final long CODE_EXPIRATION_SECONDS = 5 * 60;
    private static final long VERIFIED_EXPIRATION_SECONDS = 60 * 60;
    private static final int MAX_SEND_ATTEMPTS = 5;
    private static final int MAX_VERIFY_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Keys SIGNUP = new Keys("email:code:", "email:attempts:", "email:verified:", "email:send:count:");
    private static final Keys RESET = new Keys("password:reset:code:", "password:reset:attempts:",
            "password:reset:verified:", "password:reset:send:count:");

    private final VerificationStore store;
    private final VerificationMails mails;

    public EmailVerifications(VerificationStore store, VerificationMails mails) {
        this.store = store;
        this.mails = mails;
    }

    public void sendVerificationCode(String email) {
        send(SIGNUP, email, code -> mails.sendVerification(email, code));
    }

    public boolean verifyCode(String email, String code) {
        return verify(SIGNUP, email, code);
    }

    public boolean isVerified(String email) {
        return "true".equals(store.get(SIGNUP.verified + email));
    }

    public void clearVerification(String email) {
        store.delete(SIGNUP.verified + email);
    }

    public void sendPasswordResetCode(String email) {
        send(RESET, email, code -> mails.sendPasswordReset(email, code));
    }

    public boolean verifyPasswordResetCode(String email, String code) {
        return verify(RESET, email, code);
    }

    public void clearPasswordResetVerification(String email) {
        store.delete(RESET.verified + email);
    }

    private void send(Keys keys, String email, java.util.function.Consumer<String> mail) {
        // 1. Atomic send-count check, 2. code, 3. mail first (a failure stores nothing), 4. store, 5. reset attempts.
        if (!store.tryIncrementSendCount(keys.sendCount + email, MAX_SEND_ATTEMPTS, CODE_EXPIRATION_SECONDS)) {
            throw new TooManyAttemptsException(TooManyAttemptsException.Kind.SEND);
        }
        String code = String.format("%0" + CODE_LENGTH + "d", RANDOM.nextInt((int) Math.pow(10, CODE_LENGTH)));
        mail.accept(code);
        store.put(keys.code + email, code, CODE_EXPIRATION_SECONDS);
        store.delete(keys.attempts + email);
    }

    private boolean verify(Keys keys, String email, String code) {
        String attempts = store.get(keys.attempts + email);
        if (attempts != null && Integer.parseInt(attempts) >= MAX_VERIFY_ATTEMPTS) {
            throw new TooManyAttemptsException(TooManyAttemptsException.Kind.VERIFY);
        }
        String stored = store.get(keys.code + email);
        if (stored == null) {
            throw new ExpiredCodeException();
        }
        if (!stored.equals(code)) {
            store.incrementWithExpire(keys.attempts + email, CODE_EXPIRATION_SECONDS);
            throw new InvalidCodeException();
        }
        store.put(keys.verified + email, "true", VERIFIED_EXPIRATION_SECONDS);
        store.delete(keys.code + email);
        store.delete(keys.attempts + email);
        return true;
    }

    private record Keys(String code, String attempts, String verified, String sendCount) {
    }
}
