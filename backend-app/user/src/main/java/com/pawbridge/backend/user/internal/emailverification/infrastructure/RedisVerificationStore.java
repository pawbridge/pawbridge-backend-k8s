package com.pawbridge.backend.user.internal.emailverification.infrastructure;

import com.pawbridge.backend.user.internal.emailverification.application.VerificationStore;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** String keys and values, with the two original Lua scripts for the atomic counters. */
public final class RedisVerificationStore implements VerificationStore {
    private static final RedisScript<Long> CHECK_AND_INCREMENT = RedisScript.of(
            "local count = redis.call('GET', KEYS[1]) "
                    + "if count and tonumber(count) >= tonumber(ARGV[1]) then "
                    + "  return -1 "
                    + "end "
                    + "local newCount = redis.call('INCR', KEYS[1]) "
                    + "if newCount == 1 then "
                    + "  redis.call('EXPIRE', KEYS[1], ARGV[2]) "
                    + "end "
                    + "return newCount", Long.class);
    private static final RedisScript<Long> INCREMENT_WITH_EXPIRE = RedisScript.of(
            "local newCount = redis.call('INCR', KEYS[1]) "
                    + "if newCount == 1 then "
                    + "  redis.call('EXPIRE', KEYS[1], ARGV[1]) "
                    + "end "
                    + "return newCount", Long.class);

    private final StringRedisTemplate redis;

    public RedisVerificationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryIncrementSendCount(String key, int maxAttempts, long ttlSeconds) {
        Long result = redis.execute(CHECK_AND_INCREMENT, Collections.singletonList(key),
                String.valueOf(maxAttempts), String.valueOf(ttlSeconds));
        return result == null || result != -1;
    }

    @Override
    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    @Override
    public void put(String key, String value, long ttlSeconds) {
        redis.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void delete(String key) {
        redis.delete(key);
    }

    @Override
    public void incrementWithExpire(String key, long ttlSeconds) {
        redis.execute(INCREMENT_WITH_EXPIRE, Collections.singletonList(key), String.valueOf(ttlSeconds));
    }
}
