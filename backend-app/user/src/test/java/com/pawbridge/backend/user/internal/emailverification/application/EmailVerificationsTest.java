package com.pawbridge.backend.user.internal.emailverification.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmailVerificationsTest {
    private final List<String> events = new ArrayList<>();
    private final FakeStore store = new FakeStore(events);
    private final List<String> codes = new ArrayList<>();
    private final boolean[] failMail = {false};
    private final VerificationMails mails = new VerificationMails() {
        @Override public void sendVerification(String to, String code) { mail("signup", to, code); }
        @Override public void sendPasswordReset(String to, String code) { mail("reset", to, code); }
        private void mail(String kind, String to, String code) {
            events.add("mail:" + kind + ":" + to);
            codes.add(code);
            if (failMail[0]) throw new EmailSendingException();
        }
    };
    private final EmailVerifications verifications = new EmailVerifications(store, mails);

    @Test
    void given__signup_send__when__sending__then__count_then_mail_then_code_with_five_minutes_and_attempts_reset() {
        store.values.put("email:attempts:a@b.c", "3");

        verifications.sendVerificationCode("a@b.c");

        assertEquals(List.of("count:email:send:count:a@b.c:5:300", "mail:signup:a@b.c"), events);
        String code = codes.get(0);
        assertTrue(code.matches("\\d{6}"));
        assertEquals(code, store.values.get("email:code:a@b.c"));
        assertEquals(300L, store.ttl.get("email:code:a@b.c"));
        assertNull(store.values.get("email:attempts:a@b.c"));
    }

    @Test
    void given__mail_failure__when__sending__then__no_code_is_stored() {
        failMail[0] = true;

        assertThrows(EmailSendingException.class, () -> verifications.sendVerificationCode("a@b.c"));

        assertNull(store.values.get("email:code:a@b.c"));
    }

    @Test
    void given__five_sends__when__sending_a_sixth__then__too_many_send_attempts() {
        for (int i = 0; i < 5; i++) verifications.sendVerificationCode("a@b.c");

        var failure = assertThrows(TooManyAttemptsException.class, () -> verifications.sendVerificationCode("a@b.c"));

        assertEquals(TooManyAttemptsException.Kind.SEND, failure.kind());
        assertEquals(5, codes.size());
    }

    @Test
    void given__matching_code__when__verifying__then__verified_for_one_hour_and_code_and_attempts_removed() {
        store.values.put("email:code:a@b.c", "123456");
        store.values.put("email:attempts:a@b.c", "2");

        assertTrue(verifications.verifyCode("a@b.c", "123456"));

        assertTrue(verifications.isVerified("a@b.c"));
        assertEquals(3600L, store.ttl.get("email:verified:a@b.c"));
        assertNull(store.values.get("email:code:a@b.c"));
        assertNull(store.values.get("email:attempts:a@b.c"));
        verifications.clearVerification("a@b.c");
        assertFalse(verifications.isVerified("a@b.c"));
    }

    @Test
    void given__missing_or_wrong_code__when__verifying__then__expired_or_invalid_with_attempt_count() {
        assertThrows(ExpiredCodeException.class, () -> verifications.verifyCode("a@b.c", "111111"));
        store.values.put("email:code:a@b.c", "123456");

        for (int i = 0; i < 5; i++) {
            assertThrows(InvalidCodeException.class, () -> verifications.verifyCode("a@b.c", "000000"));
        }
        var failure = assertThrows(TooManyAttemptsException.class, () -> verifications.verifyCode("a@b.c", "123456"));

        assertEquals(TooManyAttemptsException.Kind.VERIFY, failure.kind());
        assertEquals("5", store.values.get("email:attempts:a@b.c"));
        assertEquals(300L, store.ttl.get("email:attempts:a@b.c"));
    }

    @Test
    void given__password_reset__when__sending_and_verifying__then__separate_keys_and_no_signup_verification() {
        verifications.sendPasswordResetCode("a@b.c");
        String code = codes.get(0);

        assertEquals("mail:reset:a@b.c", events.get(1));
        assertEquals(code, store.values.get("password:reset:code:a@b.c"));
        assertNull(store.values.get("email:code:a@b.c"));
        assertTrue(verifications.verifyPasswordResetCode("a@b.c", code));
        assertEquals("true", store.values.get("password:reset:verified:a@b.c"));
        assertFalse(verifications.isVerified("a@b.c"));
        verifications.clearPasswordResetVerification("a@b.c");
        assertNull(store.values.get("password:reset:verified:a@b.c"));
    }

    private static final class FakeStore implements VerificationStore {
        final Map<String, String> values = new HashMap<>();
        final Map<String, Long> ttl = new HashMap<>();
        final List<String> log;

        FakeStore(List<String> log) { this.log = log; }

        @Override public boolean tryIncrementSendCount(String key, int maxAttempts, long ttlSeconds) {
            log.add("count:" + key + ":" + maxAttempts + ":" + ttlSeconds);
            int current = Integer.parseInt(values.getOrDefault(key, "0"));
            if (current >= maxAttempts) return false;
            values.put(key, String.valueOf(current + 1));
            return true;
        }
        @Override public String get(String key) { return values.get(key); }
        @Override public void put(String key, String value, long ttlSeconds) { values.put(key, value); ttl.put(key, ttlSeconds); }
        @Override public void delete(String key) { values.remove(key); }
        @Override public void incrementWithExpire(String key, long ttlSeconds) {
            values.put(key, String.valueOf(Integer.parseInt(values.getOrDefault(key, "0")) + 1));
            ttl.put(key, ttlSeconds);
        }
    }
}
