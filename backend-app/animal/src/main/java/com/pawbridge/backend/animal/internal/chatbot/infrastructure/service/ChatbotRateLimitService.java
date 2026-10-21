package com.pawbridge.backend.animal.internal.chatbot.infrastructure.service;

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception.ChatbotRateLimitExceededException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.serialization.Mapper;
import io.github.bucket4j.redis.redisson.Bucket4jRedisson;
import java.time.Duration;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;

public class ChatbotRateLimitService {

    private final ProxyManager<String> proxyManager;

    private final long anonymousTenMinutesLimit;

    private final long anonymousDailyLimit;

    private final long ipTenMinutesLimit;

    private final long ipDailyLimit;

    private final long globalDailyLimit;

    public ChatbotRateLimitService(RedissonClient redissonClient, long anonymousTenMinutesLimit, long anonymousDailyLimit,
                                  long ipTenMinutesLimit, long ipDailyLimit, long globalDailyLimit) {
        this(proxyManager(redissonClient),anonymousTenMinutesLimit,anonymousDailyLimit,ipTenMinutesLimit,ipDailyLimit,globalDailyLimit);
    }

    ChatbotRateLimitService(ProxyManager<String> proxyManager, long anonymousTenMinutesLimit, long anonymousDailyLimit,
                           long ipTenMinutesLimit, long ipDailyLimit, long globalDailyLimit) {
        this.proxyManager=proxyManager;
        this.anonymousTenMinutesLimit=anonymousTenMinutesLimit;
        this.anonymousDailyLimit=anonymousDailyLimit;
        this.ipTenMinutesLimit=ipTenMinutesLimit;
        this.ipDailyLimit=ipDailyLimit;
        this.globalDailyLimit=globalDailyLimit;
    }

    private static ProxyManager<String> proxyManager(RedissonClient redissonClient) {
        return Bucket4jRedisson.casBasedBuilder(((Redisson) redissonClient).getCommandExecutor())
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofDays(1)))
                .keyMapper(Mapper.STRING)
                .build();
    }

    public void check(String anonymousSessionId, String ipHash) {
        boolean allowed = tryConsume("chatbot:bucket:anon:%s:10m".formatted(anonymousSessionId), anonymousTenMinutesLimit, Duration.ofMinutes(10))
                && tryConsume("chatbot:bucket:anon:%s:daily".formatted(anonymousSessionId), anonymousDailyLimit, Duration.ofDays(1))
                && tryConsume("chatbot:bucket:ip:%s:10m".formatted(ipHash), ipTenMinutesLimit, Duration.ofMinutes(10))
                && tryConsume("chatbot:bucket:ip:%s:daily".formatted(ipHash), ipDailyLimit, Duration.ofDays(1))
                && tryConsume("chatbot:bucket:global:daily", globalDailyLimit, Duration.ofDays(1));

        if (!allowed) {
            throw new ChatbotRateLimitExceededException();
        }
    }

    private boolean tryConsume(String key, long limit, Duration interval) {
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit)
                        .refillGreedy(limit, interval)
                        .build())
                .build();
        return proxyManager.getProxy(key, () -> configuration).tryConsume(1);
    }
}
