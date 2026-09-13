package com.pawbridge.animalservice.photo;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record ArchivedPhoto(byte[] bytes, String sourceHash, String storedHash,
                            String contentType, int width, int height, String recipe) {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public String objectKey() {
        String extension = switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw new PhotoArchiveFailure("OPTIMIZER_CONTRACT", true);
        };
        return "apms/photos/" + storedHash + "." + extension;
    }
}
