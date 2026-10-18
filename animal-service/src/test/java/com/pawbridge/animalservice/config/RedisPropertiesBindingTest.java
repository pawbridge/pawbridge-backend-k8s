package com.pawbridge.animalservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class RedisPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(RedisPropertiesConfiguration.class);

    @Test
    void givenRedisConnectionEnvironment_whenConfigurationLoads_thenPasswordIsBoundWithoutUrlOverride() {
        contextRunner
                .withPropertyValues(
                        "SPRING_DATA_REDIS_HOST=redis-service.databases.svc.cluster.local",
                        "SPRING_DATA_REDIS_PORT=6379",
                        "SPRING_DATA_REDIS_PASSWORD=test-password"
                )
                .run(context -> {
                    RedisProperties properties = context.getBean(RedisProperties.class);

                    assertThat(properties.getUrl()).isNull();
                    assertThat(properties.getHost()).isEqualTo("redis-service.databases.svc.cluster.local");
                    assertThat(properties.getPort()).isEqualTo(6379);
                    assertThat(properties.getPassword()).isEqualTo("test-password");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RedisProperties.class)
    static class RedisPropertiesConfiguration {
    }
}
