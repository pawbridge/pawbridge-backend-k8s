package com.pawbridge.backend.user.internal.session.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Opaque refresh tokens: 32 random bytes as unpadded base64url. Only their SHA-256 digest is stored. */
public final class RefreshTokens {
    private static final int TOKEN_BYTES = 32;
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random;

    public RefreshTokens() {
        this(new SecureRandom());
    }

    public RefreshTokens(SecureRandom random) {
        this.random = random;
    }

    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** Rejects anything this class could not have generated before it reaches the database. */
    public static boolean wellFormed(String token) {
        return token != null && TOKEN_FORMAT.matcher(token).matches();
    }

    /** Lowercase hex SHA-256, the only form of a token that is persisted or compared in SQL. */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is unavailable", missing);
        }
    }
}
