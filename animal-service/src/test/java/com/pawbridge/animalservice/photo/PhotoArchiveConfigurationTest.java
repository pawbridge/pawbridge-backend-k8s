package com.pawbridge.animalservice.photo;

import com.sun.net.httpserver.HttpServer;
import io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.CredentialsProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.RegionProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.s3.S3Client;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PhotoArchiveConfigurationTest {
    private ApplicationContextRunner context(String endpoint) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AwsAutoConfiguration.class, CredentialsProviderAutoConfiguration.class,
                        RegionProviderAutoConfiguration.class, S3AutoConfiguration.class))
                .withUserConfiguration(PhotoArchiveConfiguration.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withPropertyValues(
                        "spring.cloud.aws.credentials.access-key=public-access",
                        "spring.cloud.aws.credentials.secret-key=public-secret",
                        "spring.cloud.aws.region.static=auto",
                        "spring.cloud.aws.s3.endpoint=" + endpoint,
                        "spring.cloud.aws.s3.path-style-access-enabled=true",
                        "apms-photo-archive.enabled=true",
                        "apms-photo-archive.bucket=archive-bucket",
                        "apms-photo-archive.optimizer-url=http://127.0.0.1:1/optimize",
                        "apms-photo-archive.internal-api-key=synthetic-internal-key",
                        "apms-photo-archive.initial-delay-ms=86400000",
                        "apms-photo-archive.storage.endpoint=" + endpoint,
                        "apms-photo-archive.storage.access-key-id=archive-access",
                        "apms-photo-archive.storage.secret-access-key=archive-secret");
    }

    @Test void archive_and_public_upload_sign_requests_with_their_own_credentials() throws Exception {
        byte[] bytes = {1, 2, 3};
        var photo = new ArchivedPhoto(bytes, ArchivedPhoto.sha256(bytes), ArchivedPhoto.sha256(bytes),
                "image/png", 1, 1, "original-v1");
        Map<String, String> credentials = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            credentials.put(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var response = exchange.getResponseBody()) { response.write(bytes); }
        });
        server.start();
        try {
            context("http://127.0.0.1:" + server.getAddress().getPort()).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(S3Client.class);
                context.getBean(S3Client.class).getObjectAsBytes(r -> r.bucket("public-bucket").key("probe"));
                context.getBean(PhotoArchiveObjectStorage.class).saveAndVerify(photo);
                assertThat(credentials.get("/public-bucket/probe")).contains("Credential=public-access/");
                assertThat(credentials.get("/archive-bucket/" + photo.objectKey())).contains("Credential=archive-access/");
            });
        } finally { server.stop(0); }
    }

    @Test void missing_archive_credentials_do_not_fall_back_to_public_credentials() {
        context("http://127.0.0.1:1")
                .withPropertyValues("apms-photo-archive.storage.access-key-id=",
                        "apms-photo-archive.storage.secret-access-key=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .hasStackTraceContaining("Dedicated APMS photo storage configuration is required"));
    }

}
