package com.pawbridge.backend.community.internal.visit.application;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Day-scoped, host-only identity. No account, IP or device fingerprint. */
public final class VisitIdentity {
    public static final String NAME = "__Host-pb_visit";
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public VisitIdentity(String base64Key) {
        try { key = Base64.getDecoder().decode(base64Key); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Visit signing key must be base64."); }
        if (key.length < 32) throw new IllegalArgumentException("Visit signing key must contain at least 32 bytes.");
    }

    public LocalDate day(Instant now) { return now.atZone(ZONE).toLocalDate(); }

    public String identity(String value, LocalDate day) {
        if (value == null || value.length() > 180) return null;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4 || !parts[0].equals("v1") || !parts[1].equals(day.toString())
                || !parts[2].matches("[A-Za-z0-9_-]{43}") || !parts[3].matches("[A-Za-z0-9_-]{43}")) return null;
        try {
            if (DECODER.decode(parts[2]).length != 32 || DECODER.decode(parts[3]).length != 32) return null;
            String payload = parts[0] + "." + parts[1] + "." + parts[2];
            return MessageDigest.isEqual(mac("cookie:" + payload), DECODER.decode(parts[3])) ? parts[2] : null;
        } catch (IllegalArgumentException failure) { return null; }
    }

    public IssuedIdentity initialize(String value, Instant now) {
        LocalDate day = day(now);
        if (identity(value, day) != null) return null;
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String payload = "v1." + day + "." + ENCODER.encodeToString(bytes);
        String signed = payload + "." + ENCODER.encodeToString(mac("cookie:" + payload));
        Instant midnight = day.plusDays(1).atStartOfDay(ZONE).toInstant();
        // Floor instead of extending the identity past the next midnight.
        long seconds = Math.max(1, Duration.between(now, midnight).getSeconds());
        return new IssuedIdentity(signed, seconds);
    }

    public record IssuedIdentity(String value, long maxAgeSeconds) {}

    public String hash(LocalDate day, String identity) {
        return HexFormat.of().formatHex(mac("dedup:" + day + ":" + identity));
    }

    private byte[] mac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Visit signing unavailable."); }
    }
}
