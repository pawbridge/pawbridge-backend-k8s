package com.pawbridge.backend.animal.internal.photoarchive.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockedStatic
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow

class PhotoArchiveHttpClientTest {
    private val transport: HttpClient = mock(HttpClient::class.java)
    private val raw = byteArrayOf(1, 2, 3)

    private fun client(): PhotoArchiveHttpClient {
        val p = PhotoArchiveProperties()
        p.optimizerUrl = URI.create("http://optimizer/internal/photos/optimize")
        p.internalApiKey = "test-only-key"
        return PhotoArchiveHttpClient(transport, p)
    }

    private fun headers(bytes: ByteArray): MutableMap<String, List<String>> {
        return hashMapOf(
            "Content-Type" to listOf("image/png"),
            "X-Source-Sha256" to listOf(ArchivedPhoto.sha256(raw)),
            "X-Stored-Sha256" to listOf(ArchivedPhoto.sha256(bytes)),
            "X-Photo-Width" to listOf("1"),
            "X-Photo-Height" to listOf("1"),
            "X-Photo-Recipe" to listOf("original-v1"),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun respond(status: Int, bytes: ByteArray, headers: Map<String, List<String>>) {
        val response = mock(HttpResponse::class.java) as HttpResponse<ByteArray>
        `when`(response.statusCode()).thenReturn(status)
        `when`(response.body()).thenReturn(bytes)
        `when`(response.headers()).thenReturn(HttpHeaders.of(headers) { _, _ -> true })
        `when`(transport.sendAsync(any(HttpRequest::class.java), any<HttpResponse.BodyHandler<ByteArray>>()))
            .thenReturn(CompletableFuture.completedFuture(response))
    }

    @Test
    fun arbitrary_host_userinfo_and_nonstandard_port_are_rejected_before_network() {
        for (
            url in listOf(
                "http://127.0.0.1/x",
                "http://openapi.animal.go.kr.evil.test/x",
                "http://user@openapi.animal.go.kr/x",
                "file:///tmp/x",
                "http://openapi.animal.go.kr:8080/x",
                "https://openapi.animal.go.kr/[photo].jpg#fragment",
                "https://[openapi.animal.go.kr]/[photo].jpg",
                "https://openapi.animal.go.kr.evil.test/[photo].jpg",
                "https://user@openapi.animal.go.kr/[photo].jpg",
                "https://openapi.animal.go.kr:8080/[photo].jpg",
            )
        ) {
            assertThatThrownBy { client().download(url) }.hasMessage("SOURCE_URL")
        }
        verifyNoInteractions(transport)
    }

    @Test
    fun source_path_brackets_are_encoded_without_changing_existing_escapes_or_query() {
        val urls = mapOf(
            "https://openapi.animal.go.kr/photos/[notice]%20dog.jpg?name=[dog]&q=%5Bok%5D" to
                "https://openapi.animal.go.kr/photos/%5Bnotice%5D%20dog.jpg?name=[dog]&q=%5Bok%5D",
            "http://openapi.animal.go.kr/a%5Bb%5D[1].jpg" to
                "http://openapi.animal.go.kr/a%5Bb%5D%5B1%5D.jpg",
        )
        for (entry in urls.entries) {
            respond(200, raw, mapOf())
            clearInvocations(transport)

            assertThat(client().download(entry.key)).isEqualTo(raw)

            val request = ArgumentCaptor.forClass(HttpRequest::class.java)
            verify(transport).sendAsync(request.capture(), any<HttpResponse.BodyHandler<ByteArray>>())
            assertThat(request.value.uri().toString()).isEqualTo(entry.value)
            assertThat(request.value.headers().firstValue("X-Internal-API-Key")).isEmpty()
        }
    }

    @Test
    fun redirect_is_not_treated_as_photo_and_download_has_no_internal_key() {
        respond(302, ByteArray(0), mapOf("Location" to listOf("http://127.0.0.1/private")))

        assertThatThrownBy { client().download("https://openapi.animal.go.kr/photo") }.hasMessage("SOURCE_HTTP_302")

        val request = ArgumentCaptor.forClass(HttpRequest::class.java)
        verify(transport).sendAsync(request.capture(), any<HttpResponse.BodyHandler<ByteArray>>())
        assertThat(request.value.headers().firstValue("X-Internal-API-Key")).isEmpty()
    }

    @Test
    fun valid_optimizer_contract_preserves_original_and_sets_internal_key() {
        respond(200, raw, headers(raw))

        val photo = client().optimize(raw)

        assertThat(photo.bytes).isEqualTo(raw)
        assertThat(photo.objectKey()).isEqualTo("apms/photos/" + ArchivedPhoto.sha256(raw) + ".png")
        val request = ArgumentCaptor.forClass(HttpRequest::class.java)
        verify(transport).sendAsync(request.capture(), any<HttpResponse.BodyHandler<ByteArray>>())
        assertThat(request.value.headers().firstValue("X-Internal-API-Key")).contains("test-only-key")
    }

    @Test
    fun wrong_hash_duplicate_header_and_oversized_dimensions_fail_contract() {
        val changes = mapOf(
            "X-Source-Sha256" to listOf("wrong"),
            "X-Stored-Sha256" to listOf("wrong"),
            "X-Photo-Width" to listOf("99999999"),
            "Content-Type" to listOf("image/png", "image/jpeg"),
        )
        for (change in changes.entries) {
            val h = headers(raw)
            h[change.key] = change.value
            respond(200, raw, h)

            assertThatThrownBy { client().optimize(raw) }.hasMessage("OPTIMIZER_CONTRACT")
        }
    }

    @Test
    fun original_recipe_cannot_return_changed_bytes_even_with_valid_hash() {
        val changed = byteArrayOf(3, 2, 1)
        respond(200, changed, headers(changed))

        assertThatThrownBy { client().optimize(raw) }.hasMessage("OPTIMIZER_CONTRACT")
    }

    @Test
    fun optimizer_unavailable_is_retryable_and_does_not_fall_back_silently() {
        respond(503, ByteArray(0), mapOf())

        assertThatThrownBy { client().optimize(raw) }.isInstanceOfSatisfying(PhotoArchiveFailure::class.java) { failure ->
            assertThat(failure.slowRetry()).isFalse()
        }
    }

    @Test
    fun streaming_limit_cancels_without_content_length() {
        val body = PhotoArchiveHttpClient.LimitedBody()
        val subscription = mock(Flow.Subscription::class.java)

        body.onSubscribe(subscription)
        body.onNext(listOf(ByteBuffer.wrap(ByteArray(ArchivedPhoto.MAX_BYTES)), ByteBuffer.wrap(ByteArray(1))))

        verify(subscription).cancel()
        assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally()
    }

    @Test
    fun givenInjectedBorrowedClient__whenClosingAdapterTwice__thenLeaveTransportOwnershipWithCaller() {
        val borrowed = client()

        borrowed.close()
        borrowed.close()

        verifyNoInteractions(transport)
    }

    @Test
    fun givenPublicOwnedClient__whenClosingAdapterTwice__thenCloseCreatedJava21TransportExactlyOnce() {
        val builder = mock(HttpClient.Builder::class.java)
        `when`(builder.connectTimeout(any())).thenReturn(builder)
        `when`(builder.followRedirects(HttpClient.Redirect.NEVER)).thenReturn(builder)
        `when`(builder.build()).thenReturn(transport)
        val settings = PhotoArchiveProperties()
        settings.optimizerUrl = URI.create("http://optimizer/internal/photos/optimize")
        settings.internalApiKey = "test-only-key"
        mockStatic(HttpClient::class.java).use { factory ->
            factory.`when`<HttpClient.Builder>(MockedStatic.Verification { HttpClient.newBuilder() }).thenReturn(builder)
            val owned = PhotoArchiveHttpClient(settings)
            owned.close()
            owned.close()
        }

        verify(transport).close()
        verifyNoMoreInteractions(transport)
    }
}
