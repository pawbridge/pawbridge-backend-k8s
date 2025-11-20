package com.pawbridge.emailservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        return template;
    }

    /**
     * 발송 횟수 체크 및 증가를 원자적으로 수행하는 Lua 스크립트
     */
    @Bean
    public RedisScript<Long> checkAndIncrementScript() {
        String script =
                "local count = redis.call('GET', KEYS[1]) " +
                "if count and tonumber(count) >= tonumber(ARGV[1]) then " +
                "  return -1 " +
                "end " +
                "local newCount = redis.call('INCR', KEYS[1]) " +
                "if newCount == 1 then " +
                "  redis.call('EXPIRE', KEYS[1], ARGV[2]) " +
                "end " +
                "return newCount";
        return RedisScript.of(script, Long.class);
    }

    /**
     * 증가 및 TTL 설정을 원자적으로 수행하는 Lua 스크립트
     */
    @Bean
    public RedisScript<Long> incrementWithExpireScript() {
        String script =
                "local newCount = redis.call('INCR', KEYS[1]) " +
                "if newCount == 1 then " +
                "  redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
                "end " +
                "return newCount";
        return RedisScript.of(script, Long.class);
    }
}
