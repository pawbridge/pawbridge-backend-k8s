package com.pawbridge.backend.user.internal.password.infrastructure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class BcryptPasswordHashesTest {
    private final BcryptPasswordHashes hashes = new BcryptPasswordHashes();

    @Test
    void given__legacy_default_bcrypt_hash__when__matching__then__only_the_original_raw_password_matches() {
        String legacy = new BCryptPasswordEncoder().encode("Legacy12!");

        assertTrue(hashes.matches("Legacy12!", legacy));
        assertFalse(hashes.matches("legacy12!", legacy));
    }

    @Test
    void given__new_password__when__encoding__then__default_strength_salted_bcrypt_is_verifiable_by_the_legacy_encoder() {
        String first = hashes.encode("NewPass1!");
        String second = hashes.encode("NewPass1!");

        assertTrue(first.startsWith("$2a$10$"));
        assertNotEquals(first, second);
        assertTrue(new BCryptPasswordEncoder().matches("NewPass1!", first));
    }

    @Test
    void given__missing_or_non_bcrypt_stored_value__when__matching__then__mismatch_is_reported_instead_of_failure() {
        assertFalse(hashes.matches("NewPass1!", null));
        assertFalse(hashes.matches("NewPass1!", ""));
        assertFalse(hashes.matches("NewPass1!", "plain-text"));
    }
}
