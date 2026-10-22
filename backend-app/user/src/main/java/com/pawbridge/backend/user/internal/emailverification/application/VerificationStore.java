package com.pawbridge.backend.user.internal.emailverification.application;

/** Redis-shaped verification state; the adapter keeps the original atomic counters. */
public interface VerificationStore {
    /** Original checkAndIncrement script: false when the existing count already reached the limit. */
    boolean tryIncrementSendCount(String key, int maxAttempts, long ttlSeconds);

    String get(String key);

    void put(String key, String value, long ttlSeconds);

    void delete(String key);

    /** Original incrementWithExpire script. */
    void incrementWithExpire(String key, long ttlSeconds);
}
