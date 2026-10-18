package com.pawbridge.animalservice.chatbot.service;

import com.pawbridge.animalservice.chatbot.exception.ChatbotRateLimitExceededException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.serialization.Mapper;
import io.github.bucket4j.redis.redisson.Bucket4jRedisson;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatbotRateLimitService implements InitializingBean {

    private final RedissonClient redissonClient;
    private ProxyManager<String> proxyManager;

    @Value("${chatbot.rate-limit.anonymous.ten-minutes:5}")
    private long anonymousTenMinutesLimit;

    @Value("${chatbot.rate-limit.anonymous.daily:20}")
    private long anonymousDailyLimit;

    @Value("${chatbot.rate-limit.ip.ten-minutes:20}")
    private long ipTenMinutesLimit;

    @Value("${chatbot.rate-limit.ip.daily:100}")
    private long ipDailyLimit;

    @Value("${chatbot.rate-limit.global.daily:300}")
    private long globalDailyLimit;

    @Override
    public void afterPropertiesSet() {
        proxyManager = Bucket4jRedisson.casBasedBuilder(((Redisson) redissonClient).getCommandExecutor())
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
