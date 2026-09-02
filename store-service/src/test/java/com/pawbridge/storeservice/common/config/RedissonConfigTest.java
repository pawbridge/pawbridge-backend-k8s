package com.pawbridge.storeservice.common.config;

import org.junit.jupiter.api.Test;
import org.redisson.config.SingleServerConfig;

import static org.assertj.core.api.Assertions.assertThat;

class RedissonConfigTest {

    @Test
    void givenRedisPassword_whenBuildConfig_thenUsesAuthenticatedConnection() {
        RedissonConfig redissonConfig = new RedissonConfig("redis-master", 6379, "redis-password");

        SingleServerConfig singleServerConfig = redissonConfig.redissonConfig().useSingleServer();

        assertThat(singleServerConfig.getAddress()).isEqualTo("redis://redis-master:6379");
        assertThat(singleServerConfig.getPassword()).isEqualTo("redis-password");
    }

    @Test
    void givenBlankRedisPassword_whenBuildConfig_thenKeepsPasswordUnset() {
        RedissonConfig redissonConfig = new RedissonConfig("localhost", 6379, "");

        SingleServerConfig singleServerConfig = redissonConfig.redissonConfig().useSingleServer();

        assertThat(singleServerConfig.getAddress()).isEqualTo("redis://localhost:6379");
        assertThat(singleServerConfig.getPassword()).isNull();
    }
}
