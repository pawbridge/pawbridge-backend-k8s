package com.pawbridge.communityservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

@Configuration(proxyBeanMethods = false)
public class R2CredentialsConfig {

    @Bean
    AwsCredentialsProvider r2CredentialsProvider(
            @Value("${spring.cloud.aws.credentials.access-key}") String accessKey,
            @Value("${spring.cloud.aws.credentials.secret-key}") String secretKey,
            @Value("${pawbridge.storage.session-token:}") String sessionToken
    ) {
        if (!sessionToken.equals(sessionToken.strip())) {
            throw new IllegalArgumentException("R2 세션 토큰에 앞뒤 공백을 포함할 수 없습니다.");
        }
        AwsCredentials credentials = sessionToken.isEmpty()
                ? AwsBasicCredentials.create(accessKey, secretKey)
                : AwsSessionCredentials.create(accessKey, secretKey, sessionToken);
        return StaticCredentialsProvider.create(credentials);
    }
}
