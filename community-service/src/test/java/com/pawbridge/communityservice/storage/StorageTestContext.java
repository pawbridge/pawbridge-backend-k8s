package com.pawbridge.communityservice.storage;

import com.pawbridge.communityservice.config.R2CredentialsConfig;
import com.pawbridge.communityservice.service.S3ServiceImpl;
import io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.AwsClientCustomizer;
import io.awspring.cloud.autoconfigure.core.CredentialsProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.RegionProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/** Real production storage beans without the application, database or background workers. */
final class StorageTestContext {
    private StorageTestContext() {
    }

    static ApplicationContextRunner runner(Map<String, Object> settings) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AwsAutoConfiguration.class,
                        RegionProviderAutoConfiguration.class, CredentialsProviderAutoConfiguration.class,
                        S3AutoConfiguration.class))
                .withUserConfiguration(R2CredentialsConfig.class, S3ServiceImpl.class, TimeoutConfig.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("explicit-storage-test", settings)));
    }

    @Configuration(proxyBeanMethods = false)
    static class TimeoutConfig {
        @Bean
        AwsClientCustomizer<S3ClientBuilder> boundedStorageCalls() {
            return new AwsClientCustomizer<>() {
                @Override
                public ClientOverrideConfiguration overrideConfiguration() {
                    return ClientOverrideConfiguration.builder()
                            .apiCallTimeout(Duration.ofSeconds(15))
                            .apiCallAttemptTimeout(Duration.ofSeconds(5))
                            .build();
                }
            };
        }
    }
}
