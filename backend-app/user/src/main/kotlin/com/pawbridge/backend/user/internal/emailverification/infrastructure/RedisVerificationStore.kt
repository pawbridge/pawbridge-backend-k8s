package com.pawbridge.backend.user.internal.emailverification.infrastructure

import com.pawbridge.backend.user.internal.emailverification.application.VerificationStore
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import java.util.Collections
import java.util.concurrent.TimeUnit

/** String keys and values, with the two original Lua scripts for the atomic counters. */
class RedisVerificationStore(private val redis: StringRedisTemplate) : VerificationStore {

    override fun tryIncrementSendCount(key: String, maxAttempts: Int, ttlSeconds: Long): Boolean {
        // Explicitly nullable: the template returns null when the script has no result, and Kotlin would
        // otherwise unbox the platform type eagerly and throw a NullPointerException.
        val result: Long? = redis.execute(
            CHECK_AND_INCREMENT,
            Collections.singletonList(key),
            maxAttempts.toString(),
            ttlSeconds.toString(),
        )
        return result == null || result != -1L
    }

    override fun get(key: String): String? = redis.opsForValue().get(key)

    override fun put(key: String, value: String, ttlSeconds: Long) {
        redis.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS)
    }

    override fun delete(key: String) {
        redis.delete(key)
    }

    override fun incrementWithExpire(key: String, ttlSeconds: Long) {
        redis.execute(INCREMENT_WITH_EXPIRE, Collections.singletonList(key), ttlSeconds.toString())
    }

    private companion object {
        // Long::class.javaObjectType is java.lang.Long; Long::class.java would be the primitive long class.
        private val CHECK_AND_INCREMENT: RedisScript<Long> = RedisScript.of(
            "local count = redis.call('GET', KEYS[1]) " +
                "if count and tonumber(count) >= tonumber(ARGV[1]) then " +
                "  return -1 " +
                "end " +
                "local newCount = redis.call('INCR', KEYS[1]) " +
                "if newCount == 1 then " +
                "  redis.call('EXPIRE', KEYS[1], ARGV[2]) " +
                "end " +
                "return newCount",
            Long::class.javaObjectType,
        )

        private val INCREMENT_WITH_EXPIRE: RedisScript<Long> = RedisScript.of(
            "local newCount = redis.call('INCR', KEYS[1]) " +
                "if newCount == 1 then " +
                "  redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
                "end " +
                "return newCount",
            Long::class.javaObjectType,
        )
    }
}
