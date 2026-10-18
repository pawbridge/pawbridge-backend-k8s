package com.pawbridge.communityservice.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.pawbridge.communityservice.exception.InvalidImageFormatException;
import com.pawbridge.communityservice.service.S3ServiceImpl;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Actual SDK/HTTP/object storage; S3Mock does not validate real R2 credentials or signatures. */
@Testcontainers
class S3StorageIntegrationTest {
    private static final String BUCKET = "community-storage-test";
    private static final String PUBLIC_BASE = "https://storage-test.invalid";
    static final byte[] PHOTO = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jAukAAAAASUVORK5CYII=");

    @Container
    static final GenericContainer<?> storage = new GenericContainer<>(DockerImageName.parse(
            "adobe/s3mock@sha256:65cf60155a2e235fe7d5bf6c633747d6fc7ed93f9f5a6727d86470026b83c2a2"))
            .withExposedPorts(9090)
            .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", BUCKET)
            .withEnv("JAVA_TOOL_OPTIONS", "-Xms64m -Xmx256m")
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                    .withMemory(512 * 1024L * 1024)
                    .withNanoCPUs(1_000_000_000L)
                    .withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0),
                            new ExposedPort(9090))))
            .waitingFor(Wait.forHttp("/favicon.ico").forPort(9090).forStatusCode(200))
            .withStartupTimeout(Duration.ofSeconds(90));

    private String prefix;

    @BeforeEach
    void ownObjectNamespace() {
        prefix = "dev/storage-tests/" + UUID.randomUUID() + "/";
    }

    @Test
    void givenReportPhoto_whenUploadReadAndDelete_thenPreserveBytesAndMetadataInOwnedPath() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var client = context.getBean(S3Client.class);
            var service = context.getBean(S3ServiceImpl.class);
            String url = service.uploadReportImages(new MockMultipartFile[]{photo()}).get(0);
            String key = key(url);

            assertThat(key).startsWith(prefix + "reports/images/").endsWith(".png");
            var downloaded = client.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET).key(key).build());
            assertThat(downloaded.asByteArray()).isEqualTo(PHOTO);
            assertThat(downloaded.response().contentType()).isEqualTo("image/png");
            assertThat(downloaded.response().contentLength()).isEqualTo((long) PHOTO.length);

            service.deleteFile(url);
            assertMissing(client, key);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/png", "video/mp4"})
    void givenPostMedia_whenUpload_thenStoreBytesUnderTheMatchingDevFolder(String contentType) {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var client = context.getBean(S3Client.class);
            var service = context.getBean(S3ServiceImpl.class);
            String extension = contentType.equals("image/png") ? ".png" : ".mp4";
            var file = new MockMultipartFile("files", "sample" + extension, contentType, PHOTO);
            String url = service.uploadImages(new MockMultipartFile[]{file}).get(0);
            String key = key(url);

            assertThat(key).startsWith(prefix + (contentType.equals("image/png")
                    ? "posts/images/" : "posts/videos/")).endsWith(extension);
            var downloaded = client.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET).key(key).build());
            assertThat(downloaded.asByteArray()).isEqualTo(PHOTO);
            assertThat(downloaded.response().contentType()).isEqualTo(contentType);
            service.deleteFile(url);
            assertMissing(client, key);
        });
    }

    @Test
    void givenInvalidSecondReportFile_whenUploadFails_thenRemoveTheActuallyUploadedFirstObject() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var client = context.getBean(S3Client.class);
            var service = context.getBean(S3ServiceImpl.class);
            var invalid = new MockMultipartFile("photos", "invalid.txt", "text/plain", new byte[]{1});

            assertThatThrownBy(() -> service.uploadReportImages(new MockMultipartFile[]{photo(), invalid}))
                    .isInstanceOf(RuntimeException.class)
                    .hasCauseInstanceOf(InvalidImageFormatException.class);
            assertThat(client.listObjectsV2(request -> request.bucket(BUCKET).prefix(prefix)).contents()).isEmpty();
        });
    }

    @Test
    void givenObjectsOutsideOwnedPrefix_whenDeleteRequested_thenPreserveBothObjects() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var client = context.getBean(S3Client.class);
            var service = context.getBean(S3ServiceImpl.class);
            String productionKey = "reports/images/" + UUID.randomUUID() + ".png";
            String otherDevKey = "dev/another-test/" + UUID.randomUUID() + ".png";
            for (String key : new String[]{productionKey, otherDevKey}) {
                client.putObject(PutObjectRequest.builder().bucket(BUCKET).key(key).build(),
                        RequestBody.fromBytes(PHOTO));
                service.deleteFile(PUBLIC_BASE + "/" + key);
                assertThat(client.getObjectAsBytes(request -> request.bucket(BUCKET).key(key)).asByteArray())
                        .isEqualTo(PHOTO);
            }
        });
    }

    @Test
    void givenOwnedObjectWithForeignPublicUrl_whenDeleteRequested_thenPreserveObject() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var client = context.getBean(S3Client.class);
            var service = context.getBean(S3ServiceImpl.class);
            String url = service.uploadReportImages(new MockMultipartFile[]{photo()}).get(0);
            String key = key(url);

            service.deleteFile("https://another-storage.invalid/" + key);
            assertThat(client.getObjectAsBytes(request -> request.bucket(BUCKET).key(key)).asByteArray())
                    .isEqualTo(PHOTO);
            service.deleteFile(url);
            assertMissing(client, key);
        });
    }

    private org.springframework.boot.test.context.runner.ApplicationContextRunner runner() {
        return StorageTestContext.runner(Map.of(
                "spring.cloud.aws.credentials.access-key", "storage-test-only",
                "spring.cloud.aws.credentials.secret-key", "storage-test-only",
                "spring.cloud.aws.region.static", "auto",
                "spring.cloud.aws.s3.endpoint", "http://" + storage.getHost() + ":" + storage.getMappedPort(9090),
                "spring.cloud.aws.s3.path-style-access-enabled", true,
                "spring.cloud.aws.s3.chunked-encoding-enabled", false,
                "spring.cloud.aws.s3.bucket", BUCKET,
                "pawbridge.storage.public-base-url", PUBLIC_BASE,
                "pawbridge.storage.object-prefix", prefix,
                "pawbridge.storage.session-token", ""));
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile("photos", "sample.png", "image/png", PHOTO);
    }

    private static String key(String url) {
        assertThat(url).startsWith(PUBLIC_BASE + "/");
        return url.substring(PUBLIC_BASE.length() + 1);
    }

    private static void assertMissing(S3Client client, String key) {
        assertThatThrownBy(() -> client.getObjectAsBytes(request -> request.bucket(BUCKET).key(key)))
                .isInstanceOfSatisfying(S3Exception.class, failure -> assertThat(failure.statusCode()).isEqualTo(404));
    }
}
