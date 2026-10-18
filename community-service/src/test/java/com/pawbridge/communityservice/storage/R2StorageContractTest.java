package com.pawbridge.communityservice.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pawbridge.communityservice.service.S3ServiceImpl;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Prepared only: excluded from ordinary tests; real R2 writes need a separate approved run. */
@Tag("real-r2")
class R2StorageContractTest {
    static final String ENDPOINT = "https://3e28b8ba8375b9010f6a426c1cb9755f.r2.cloudflarestorage.com";
    static final String BUCKET = "pawbridge-public-images";
    private static final String PUBLIC_BASE = "https://images.pawbridge.kr";

    @Test
    void givenApprovedDevTarget_whenUploadReadAndDelete_thenVerifyOneTemporaryObject() {
        String runId = UUID.randomUUID().toString();
        Map<String, Object> settings = guardedSettings(System.getenv(), runId);
        String prefix = (String) settings.get("pawbridge.storage.object-prefix");
        // One tiny synthetic image. No user photos, DB, bucket configuration or account changes.
        byte[] photo = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jAukAAAAASUVORK5CYII=");

        StorageTestContext.runner(settings).run(context -> {
            assertThat(context).hasNotFailed();
            var client = context.getBean(S3Client.class);
            var service = context.getBean(S3ServiceImpl.class);
            Throwable operationFailure = null;
            try {
                String url = service.uploadReportImages(new MockMultipartFile[]{
                        new MockMultipartFile("photos", "contract.png", "image/png", photo)}).get(0);
                String key = url.substring((PUBLIC_BASE + "/").length());
                assertThat(key).startsWith(prefix + "reports/images/").endsWith(".png");
                var downloaded = client.getObjectAsBytes(request -> request.bucket(BUCKET).key(key));
                assertThat(downloaded.asByteArray()).isEqualTo(photo);
                assertThat(downloaded.response().contentType()).isEqualTo("image/png");
                service.deleteFile(url);
                assertMissing(client, key);
            } catch (RuntimeException | AssertionError failure) {
                operationFailure = failure;
                throw failure;
            } finally {
                // Covers a PUT response lost after storage succeeded: discover only this run's prefix.
                // Refuse cleanup if the unique namespace unexpectedly contains multiple objects.
                try {
                    var remaining = client.listObjectsV2(request -> request.bucket(BUCKET).prefix(prefix).maxKeys(2));
                    if (remaining.isTruncated() || remaining.contents().size() > 1) {
                        throw new IllegalStateException("Unexpected objects; no cleanup performed under " + prefix);
                    }
                    for (var object : remaining.contents()) {
                        if (!object.key().startsWith(prefix + "reports/images/")) {
                            throw new IllegalStateException("Unexpected key; no cleanup performed under " + prefix);
                        }
                        client.deleteObject(request -> request.bucket(BUCKET).key(object.key()));
                        assertMissing(client, object.key());
                    }
                } catch (RuntimeException | AssertionError cleanupFailure) {
                    if (operationFailure == null) {
                        throw cleanupFailure;
                    }
                    operationFailure.addSuppressed(cleanupFailure);
                }
            }
        });
    }

    static Map<String, Object> guardedSettings(Map<String, String> environment, String runId) {
        if (!"yes".equals(environment.get("PAWBRIDGE_R2_CONTRACT_APPROVED"))) {
            throw new IllegalArgumentException("Separate approval is required before real R2 access");
        }
        if (!ENDPOINT.equals(environment.get("R2_CONTRACT_ENDPOINT"))
                || !BUCKET.equals(environment.get("R2_CONTRACT_BUCKET"))) {
            throw new IllegalArgumentException("Explicitly approved R2 endpoint and bucket are required");
        }
        if (!runId.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) {
            throw new IllegalArgumentException("Unique UUID run identifier is required");
        }
        var settings = new HashMap<String, Object>();
        settings.put("spring.cloud.aws.credentials.access-key", required(environment, "R2_CONTRACT_ACCESS_KEY_ID"));
        settings.put("spring.cloud.aws.credentials.secret-key", required(environment, "R2_CONTRACT_SECRET_ACCESS_KEY"));
        settings.put("spring.cloud.aws.region.static", "auto");
        settings.put("spring.cloud.aws.s3.endpoint", ENDPOINT);
        settings.put("spring.cloud.aws.s3.bucket", BUCKET);
        settings.put("spring.cloud.aws.s3.path-style-access-enabled", true);
        settings.put("spring.cloud.aws.s3.chunked-encoding-enabled", false);
        settings.put("pawbridge.storage.public-base-url", PUBLIC_BASE);
        settings.put("pawbridge.storage.object-prefix", "dev/contract-tests/" + runId + "/");
        String session = environment.getOrDefault("R2_CONTRACT_SESSION_TOKEN", "");
        if (!session.equals(session.strip())) {
            throw new IllegalArgumentException("R2_CONTRACT_SESSION_TOKEN must not be padded");
        }
        settings.put("pawbridge.storage.session-token", session);
        return Map.copyOf(settings);
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " is missing or padded");
        }
        return value;
    }

    private static void assertMissing(S3Client client, String key) {
        assertThatThrownBy(() -> client.getObjectAsBytes(request -> request.bucket(BUCKET).key(key)))
                .isInstanceOfSatisfying(S3Exception.class, failure -> assertThat(failure.statusCode()).isEqualTo(404));
    }
}
