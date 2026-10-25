package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.image.infrastructure.S3AnimalImages
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.S3ProductImages
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.web.multipart.MultipartFile
import software.amazon.awssdk.services.s3.S3Client
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.UncheckedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Duration
import java.util.HexFormat
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.function.Function
import java.util.function.Supplier

/**
 * Runs the real shared {@code postS3Client} (path style, chunked encoding disabled) against an
 * in-process storage that reads exactly Content-Length bytes, like MinIO and R2 do.
 *
 * A request body that cannot be re-read leaves the client sending fewer bytes than it announced.
 * The server then waits for the rest and the client waits for the response, so the call never
 * returns. The timeout below turns that hang into a failure.
 */
class ImageUploadStorageHttpTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private lateinit var storage: HttpServer
    private lateinit var client: S3Client
    private val received = CopyOnWriteArrayList<ReceivedObject>()

    @BeforeEach
    fun startStorageAndBuildSharedClient() {
        storage = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        storage.createContext("/", HttpHandler { exchange -> receiveObject(exchange) })
        storage.executor = Executors.newCachedThreadPool(
            ThreadFactory { runnable ->
                val thread = Thread(runnable, "fake-storage")
                thread.isDaemon = true
                thread
            },
        )
        storage.start()

        val settingValues = HashMap<String, String>()
        settingValues["PAWBRIDGE_JWT_SECRET"] = "image-upload-synthetic-key-only-never-for-production-0123456789"
        settingValues["PAWBRIDGE_STORAGE_ENDPOINT"] = "http://127.0.0.1:" + storage.address.port
        settingValues["PAWBRIDGE_STORAGE_BUCKET"] = BUCKET
        settingValues["PAWBRIDGE_STORAGE_PUBLIC_BASE_URL"] = "https://assets.invalid"
        settingValues["PAWBRIDGE_STORAGE_OBJECT_PREFIX"] = "image-upload-test/"
        settingValues["PAWBRIDGE_STORAGE_ACCESS_KEY_ID"] = "synthetic-access"
        settingValues["PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY"] = "synthetic-secret-only"
        settingValues["PAWBRIDGE_STORAGE_REGION"] = "us-east-1"
        val settings = PostWriteSettings.fromEnvironment(Function { key -> settingValues[key] })
        client = PostWriteConfiguration().postS3Client(settings)
    }

    @AfterEach
    fun stopStorageAndCloseClient() {
        client.close()
        storage.stop(0)
    }

    @ParameterizedTest
    @ValueSource(ints = [3, ABOVE_SDK_RESET_BUFFER, 2 * 1024 * 1024])
    fun given__product_image__when__uploading_through_shared_client__then__storage_receives_every_byte_and_call_returns(
        size: Int,
    ) {
        val imageBytes = randomBytes(size)
        val images = S3ProductImages(client, BUCKET, "https://assets.invalid")
        val file = diskBackedFile("photo.png", "image/png", imageBytes)

        val url = uploadWithTimeout(Supplier { images.uploadImage(file) })

        assertStoredExactly(url, "/$BUCKET/products/", imageBytes, "image/png")
    }

    @ParameterizedTest
    @ValueSource(ints = [3, ABOVE_SDK_RESET_BUFFER, 2 * 1024 * 1024])
    fun given__animal_image__when__uploading_through_shared_client__then__storage_receives_every_byte_and_call_returns(
        size: Int,
    ) {
        val imageBytes = randomBytes(size)
        val images = S3AnimalImages(client, BUCKET, "https://assets.invalid")
        val file = diskBackedFile("photo.jpg", "image/jpeg", imageBytes)

        val url = uploadWithTimeout(Supplier { images.uploadImage(file) })

        assertStoredExactly(url, "/$BUCKET/animals/", imageBytes, "image/jpeg")
    }

    /**
     * Spring Boot writes uploaded parts to disk by default (file-size-threshold is 0), and Tomcat
     * opens them with `Files.newInputStream`. That stream can neither mark nor reset and the
     * SDK cannot make it resettable, unlike the in-memory stream of `MockMultipartFile` (or a
     * `FileInputStream`), which would hide the bug.
     */
    private fun diskBackedFile(originalFilename: String, contentType: String, content: ByteArray): MultipartFile {
        val uploadedPart = Files.write(temporaryDirectory.resolve(originalFilename), content)
        return DiskBackedMultipartFile(uploadedPart, originalFilename, contentType)
    }

    private fun uploadWithTimeout(upload: Supplier<String>): String {
        return assertTimeoutPreemptively(UPLOAD_TIMEOUT, ThrowingSupplier { upload.get() })
    }

    private fun assertStoredExactly(url: String, expectedPathPrefix: String, expectedBytes: ByteArray, expectedContentType: String) {
        assertThat(received).hasSize(1)
        val stored = received[0]
        assertThat(stored.method).isEqualTo("PUT")
        assertThat(stored.path).startsWith(expectedPathPrefix)
        assertThat(stored.contentType).isEqualTo(expectedContentType)
        assertThat(stored.declaredLength).isEqualTo(expectedBytes.size.toLong())
        assertThat(stored.body).isEqualTo(expectedBytes)
        assertThat(url).startsWith("https://assets.invalid/")
    }

    private fun receiveObject(exchange: HttpExchange) {
        exchange.use {
            val declaredLength = exchange.requestHeaders.getFirst("Content-Length").toLong()
            val body = exchange.requestBody.readNBytes(Math.toIntExact(declaredLength))
            received.add(
                ReceivedObject(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("Content-Type"),
                    declaredLength,
                    body,
                ),
            )
            exchange.responseHeaders.add("ETag", "\"" + md5Hex(body) + "\"")
            exchange.sendResponseHeaders(200, -1)
        }
    }

    private class DiskBackedMultipartFile(
        private val file: Path,
        private val originalFilename: String,
        private val contentType: String,
    ) : MultipartFile {
        override fun getName(): String = "file"

        override fun getOriginalFilename(): String = originalFilename

        override fun getContentType(): String = contentType

        override fun isEmpty(): Boolean = size == 0L

        override fun getSize(): Long {
            try {
                return Files.size(file)
            } catch (failure: IOException) {
                throw UncheckedIOException(failure)
            }
        }

        override fun getBytes(): ByteArray = Files.readAllBytes(file)

        override fun getInputStream(): InputStream = Files.newInputStream(file)

        override fun transferTo(destination: File) {
            Files.copy(file, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private class ReceivedObject(
        val method: String,
        val path: String,
        val contentType: String?,
        val declaredLength: Long,
        val body: ByteArray,
    )

    companion object {
        private val UPLOAD_TIMEOUT: Duration = Duration.ofSeconds(20)
        private const val BUCKET = "image-upload-test-bucket"
        private const val ABOVE_SDK_RESET_BUFFER = 300 * 1024

        /** The SDK checks the PutObject ETag against the MD5 of what it sent, as S3-compatible stores return. */
        private fun md5Hex(body: ByteArray): String {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(body))
            } catch (failure: NoSuchAlgorithmException) {
                throw IllegalStateException(failure)
            }
        }

        private fun randomBytes(size: Int): ByteArray {
            val bytes = ByteArray(size)
            Random(size.toLong()).nextBytes(bytes)
            return bytes
        }
    }
}
