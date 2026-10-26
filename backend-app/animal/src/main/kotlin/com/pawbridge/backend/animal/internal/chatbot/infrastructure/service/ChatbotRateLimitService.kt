package com.pawbridge.backend.animal.internal.chatbot.infrastructure.service

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception.ChatbotRateLimitExceededException
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.distributed.serialization.Mapper
import io.github.bucket4j.redis.redisson.Bucket4jRedisson
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import java.time.Duration

// 시험이 쓰는 package-private 생성자(ProxyManager)는 internal constructor(JVM에서는 public)로 둔다.
class ChatbotRateLimitService internal constructor(
    private val proxyManager: ProxyManager<String>,
    private val anonymousTenMinutesLimit: Long,
    private val anonymousDailyLimit: Long,
    private val ipTenMinutesLimit: Long,
    private val ipDailyLimit: Long,
    private val globalDailyLimit: Long,
) {

    constructor(
        redissonClient: RedissonClient,
        anonymousTenMinutesLimit: Long,
        anonymousDailyLimit: Long,
        ipTenMinutesLimit: Long,
        ipDailyLimit: Long,
        globalDailyLimit: Long,
    ) : this(
        proxyManager(redissonClient),
        anonymousTenMinutesLimit,
        anonymousDailyLimit,
        ipTenMinutesLimit,
        ipDailyLimit,
        globalDailyLimit,
    )

    fun check(anonymousSessionId: String?, ipHash: String?) {
        val allowed = tryConsume(
            "chatbot:bucket:anon:%s:10m".format(anonymousSessionId),
            anonymousTenMinutesLimit,
            Duration.ofMinutes(10),
        ) &&
            tryConsume(
                "chatbot:bucket:anon:%s:daily".format(anonymousSessionId),
                anonymousDailyLimit,
                Duration.ofDays(1),
            ) &&
            tryConsume("chatbot:bucket:ip:%s:10m".format(ipHash), ipTenMinutesLimit, Duration.ofMinutes(10)) &&
            tryConsume("chatbot:bucket:ip:%s:daily".format(ipHash), ipDailyLimit, Duration.ofDays(1)) &&
            tryConsume("chatbot:bucket:global:daily", globalDailyLimit, Duration.ofDays(1))

        if (!allowed) {
            throw ChatbotRateLimitExceededException()
        }
    }

    private fun tryConsume(key: String, limit: Long, interval: Duration): Boolean {
        val configuration = BucketConfiguration.builder()
            .addLimit(
                Bandwidth.builder()
                    .capacity(limit)
                    .refillGreedy(limit, interval)
                    .build(),
            )
            .build()
        return proxyManager.getProxy(key) { configuration }.tryConsume(1)
    }

    private companion object {
        private fun proxyManager(redissonClient: RedissonClient): ProxyManager<String> {
            return Bucket4jRedisson.casBasedBuilder((redissonClient as Redisson).commandExecutor)
                .expirationAfterWrite(
                    ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofDays(1)),
                )
                .keyMapper(Mapper.STRING)
                .build()
        }
    }
}
