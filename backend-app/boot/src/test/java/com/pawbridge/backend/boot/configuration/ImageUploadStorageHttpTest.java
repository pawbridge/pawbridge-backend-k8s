package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.pawbridge.backend.animal.internal.image.infrastructure.S3AnimalImages;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.S3ProductImages;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Runs the real shared {@code postS3Client} (path style, chunked encoding disabled) against an
 * in-process storage that reads exactly Content-Length bytes, like MinIO and R2 do.
 *
 * <p>A request body that cannot be re-read leaves the client sending fewer bytes than it announced.
 * The server then waits for the rest and the client waits for the response, so the call never
 * returns. The timeout below turns that hang into a failure.
 */
class ImageUploadStorageHttpTest {
    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(20);
    private static final String BUCKET = "image-upload-test-bucket";
    private static final int ABOVE_SDK_RESET_BUFFER = 300 * 1024;

    @TempDir
    private Path temporaryDirectory;

    private HttpServer storage;
    private S3Client client;
    private final CopyOnWriteArrayList<ReceivedObject> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startStorageAndBuildSharedClient() throws IOException {
        storage = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        storage.createContext("/", this::receiveObject);
        storage.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-storage");
            thread.setDaemon(true);
            return thread;
        }));
        storage.start();

        Map<String, String> settingValues = new HashMap<>();
        settingValues.put("PAWBRIDGE_JWT_SECRET", "image-upload-synthetic-key-only-never-for-production-0123456789");
        settingValues.put("PAWBRIDGE_STORAGE_ENDPOINT", "http://127.0.0.1:" + storage.getAddress().getPort());
        settingValues.put("PAWBRIDGE_STORAGE_BUCKET", BUCKET);
        settingValues.put("PAWBRIDGE_STORAGE_PUBLIC_BASE_URL", "https://assets.invalid");
        settingValues.put("PAWBRIDGE_STORAGE_OBJECT_PREFIX", "image-upload-test/");
        settingValues.put("PAWBRIDGE_STORAGE_ACCESS_KEY_ID", "synthetic-access");
        settingValues.put("PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY", "synthetic-secret-only");
        settingValues.put("PAWBRIDGE_STORAGE_REGION", "us-east-1");
        PostWriteSettings settings = PostWriteSettings.fromEnvironment(settingValues::get);
        client = new PostWriteConfiguration().postS3Client(settings);
    }

    @AfterEach
    void stopStorageAndCloseClient() {
        client.close();
        storage.stop(0);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, ABOVE_SDK_RESET_BUFFER, 2 * 1024 * 1024})
    void given__product_image__when__uploading_through_shared_client__then__storage_receives_every_byte_and_call_returns(int size) throws IOException {
        byte[] imageBytes = randomBytes(size);
        S3ProductImages images = new S3ProductImages(client, BUCKET, "https://assets.invalid");
        MultipartFile file = diskBackedFile("photo.png", "image/png", imageBytes);

        String url = uploadWithTimeout(() -> images.uploadImage(file));

        assertStoredExactly(url, "/" + BUCKET + "/products/", imageBytes, "image/png");
    }

    @ParameterizedTest
    @ValueSource(ints = {3, ABOVE_SDK_RESET_BUFFER, 2 * 1024 * 1024})
    void given__animal_image__when__uploading_through_shared_client__then__storage_receives_every_byte_and_call_returns(int size) throws IOException {
        byte[] imageBytes = randomBytes(size);
        S3AnimalImages images = new S3AnimalImages(client, BUCKET, "https://assets.invalid");
        MultipartFile file = diskBackedFile("photo.jpg", "image/jpeg", imageBytes);

        String url = uploadWithTimeout(() -> images.uploadImage(file));

        assertStoredExactly(url, "/" + BUCKET + "/animals/", imageBytes, "image/jpeg");
    }

    /**
     * Spring Boot writes uploaded parts to disk by default (file-size-threshold is 0), and Tomcat
     * opens them with {@code Files.newInputStream}. That stream can neither mark nor reset and the
     * SDK cannot make it resettable, unlike the in-memory stream of {@code MockMultipartFile} (or a
     * {@code FileInputStream}), which would hide the bug.
     */
    private MultipartFile diskBackedFile(String originalFilename, String contentType, byte[] content) throws IOException {
        Path uploadedPart = Files.write(temporaryDirectory.resolve(originalFilename), content);
        return new DiskBackedMultipartFile(uploadedPart, originalFilename, contentType);
    }

    private String uploadWithTimeout(Supplier<String> upload) {
        return assertTimeoutPreemptively(UPLOAD_TIMEOUT, upload::get);
    }

    private void assertStoredExactly(String url, String expectedPathPrefix, byte[] expectedBytes, String expectedContentType) {
        assertThat(received).hasSize(1);
        ReceivedObject object = received.getFirst();
        assertThat(object.method()).isEqualTo("PUT");
        assertThat(object.path()).startsWith(expectedPathPrefix);
        assertThat(object.contentType()).isEqualTo(expectedContentType);
        assertThat(object.declaredLength()).isEqualTo(expectedBytes.length);
        assertThat(object.body()).isEqualTo(expectedBytes);
        assertThat(url).startsWith("https://assets.invalid/");
    }

    private void receiveObject(HttpExchange exchange) throws IOException {
        try (exchange) {
            long declaredLength = Long.parseLong(exchange.getRequestHeaders().getFirst("Content-Length"));
            byte[] body = exchange.getRequestBody().readNBytes(Math.toIntExact(declaredLength));
            received.add(new ReceivedObject(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    declaredLength,
                    body));
            exchange.getResponseHeaders().add("ETag", "\"" + md5Hex(body) + "\"");
            exchange.sendResponseHeaders(200, -1);
        }
    }

    /** The SDK checks the PutObject ETag against the MD5 of what it sent, as S3-compatible stores return. */
    private static String md5Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(body));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return bytes;
    }

    private record DiskBackedMultipartFile(Path file, String originalFilename, String contentType) implements MultipartFile {
        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return getSize() == 0;
        }

        @Override
        public long getSize() {
            try {
                return Files.size(file);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public byte[] getBytes() throws IOException {
            return Files.readAllBytes(file);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return Files.newInputStream(file);
        }

        @Override
        public void transferTo(File destination) throws IOException {
            Files.copy(file, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record ReceivedObject(String method, String path, String contentType, long declaredLength, byte[] body) {
    }
}
