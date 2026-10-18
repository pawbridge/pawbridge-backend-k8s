package com.pawbridge.communityservice.config;

import io.awspring.cloud.autoconfigure.core.AwsClientCustomizer;
import io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.CredentialsProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.RegionProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class R2CredentialsConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AwsAutoConfiguration.class,
                    RegionProviderAutoConfiguration.class, CredentialsProviderAutoConfiguration.class,
                    S3AutoConfiguration.class))
            .withUserConfiguration(R2CredentialsConfig.class)
            .withPropertyValues("spring.cloud.aws.credentials.access-key=test-access",
                    "spring.cloud.aws.credentials.secret-key=test-secret",
                    "spring.cloud.aws.region.static=auto",
                    "spring.cloud.aws.s3.endpoint=http://unconfigured.invalid",
                    "spring.cloud.aws.s3.path-style-access-enabled=true");

    @Test
    void givenNoSessionToken__whenAutoConfigureS3__thenKeepBasicCredentials() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(S3Client.class)
                    .hasSingleBean(AwsCredentialsProvider.class);
            var credentials = context.getBean(AwsCredentialsProvider.class).resolveCredentials();
            assertThat(credentials).isInstanceOf(AwsBasicCredentials.class);
            assertThat(credentials.accessKeyId()).isEqualTo("test-access");
            assertThat(credentials.secretAccessKey()).isEqualTo("test-secret");
        });
    }

    @Test
    void givenSessionToken__whenAutoConfigureS3__thenUseThreePartCredentials() {
        runner.withPropertyValues("pawbridge.storage.session-token=test-session").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(S3Client.class)
                    .hasSingleBean(AwsCredentialsProvider.class);
            var credentials = context.getBean(AwsCredentialsProvider.class).resolveCredentials();
            assertThat(credentials).isInstanceOf(AwsSessionCredentials.class);
            assertThat(credentials.accessKeyId()).isEqualTo("test-access");
            assertThat(credentials.secretAccessKey()).isEqualTo("test-secret");
            assertThat(((AwsSessionCredentials) credentials).sessionToken()).isEqualTo("test-session");
        });
    }

    @Test
    void givenPaddedSessionToken__whenStart__thenRejectWithoutBasicFallback() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("invalid-session", Map.of("pawbridge.storage.session-token", " test-session "))))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    void givenSessionToken__whenUploadWithAutoConfiguredS3__thenSignRequestWithSessionHeader() throws Exception {
        var request = signedUpload("test-session");

        assertThat(request.firstMatchingHeader("X-Amz-Security-Token")).contains("test-session");
        assertThat(request.firstMatchingHeader("Authorization")).hasValueSatisfying(value ->
                assertThat(value).contains("Credential=test-access/"));
        assertThat(request.encodedPath()).isEqualTo("/pawbridge-public-images/dev/reports/images/test.png");
    }

    @Test
    void givenNoSessionToken__whenUploadWithAutoConfiguredS3__thenKeepBasicSigningWithoutSessionHeader() throws Exception {
        var request = signedUpload("");

        assertThat(request.firstMatchingHeader("X-Amz-Security-Token")).isEmpty();
        assertThat(request.firstMatchingHeader("Authorization")).hasValueSatisfying(value ->
                assertThat(value).contains("Credential=test-access/"));
    }

    private software.amazon.awssdk.http.SdkHttpRequest signedUpload(String sessionToken) throws Exception {
        SdkHttpClient transport = mock(SdkHttpClient.class);
        ExecutableHttpRequest executable = mock(ExecutableHttpRequest.class);
        when(transport.prepareRequest(any(HttpExecuteRequest.class))).thenReturn(executable);
        when(executable.call()).thenReturn(HttpExecuteResponse.builder()
                .response(SdkHttpResponse.builder().statusCode(200).build())
                .responseBody(AbortableInputStream.create(new ByteArrayInputStream(new byte[0])))
                .build());

        runner.withPropertyValues("pawbridge.storage.session-token=" + sessionToken)
                .withBean(SdkHttpClient.class, () -> transport)
                .withUserConfiguration(RecordingTransportConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(S3Client.class).putObject(PutObjectRequest.builder()
                            .bucket("pawbridge-public-images").key("dev/reports/images/test.png").build(),
                            RequestBody.fromBytes(new byte[]{1}));
                });

        ArgumentCaptor<HttpExecuteRequest> sent = ArgumentCaptor.forClass(HttpExecuteRequest.class);
        verify(transport).prepareRequest(sent.capture());
        return sent.getValue().httpRequest();
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingTransportConfig {
        @Bean
        AwsClientCustomizer<S3ClientBuilder> recordingTransportCustomizer(SdkHttpClient transport) {
            return new AwsClientCustomizer<>() {
                @Override
                public SdkHttpClient httpClient() {
                    return transport;
                }
            };
        }
    }
}
