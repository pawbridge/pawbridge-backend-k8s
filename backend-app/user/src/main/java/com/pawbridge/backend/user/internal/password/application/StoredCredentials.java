package com.pawbridge.backend.user.internal.password.application;

public record StoredCredentials(String provider, String password) {
    @Override
    public String toString() {
        // Never expose the stored hash through logs or exception messages.
        return "StoredCredentials[provider=" + provider + ", password=<redacted>]";
    }
}
