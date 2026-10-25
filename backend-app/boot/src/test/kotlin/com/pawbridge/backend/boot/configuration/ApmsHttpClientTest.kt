package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.support.anyValue
import com.pawbridge.backend.support.capture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.util.ReflectionTestUtils
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.function.Supplier

class ApmsHttpClientTest {
    @Test
    fun givenExplicitProviderBase__whenProductionTransportIsCreated__thenHttpsAndFiniteConnectWithoutRedirects() {
        ApmsHttpClient(BASE, ObjectMapper()).use { transport ->
            val client = ReflectionTestUtils.getField(transport, "client") as HttpClient
            assertThat(client.connectTimeout()).contains(Duration.ofSeconds(2))
            assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER)
            assertThat(ReflectionTestUtils.getField(transport, "ownsClient")).isEqualTo(true)
        }
        val invalidBases = arrayOf(
            "", "http://localhost:8080", "https://user:password@example.test",
            "https://example.test?serviceKey=fixture", "https://example.test#fragment", "https:///missing-authority",
        )
        for (invalid in invalidBases) {
            assertThatThrownBy { ApmsHttpClient.baseUri(invalid) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("APMS base URL must be an explicit HTTPS provider URL")
                .hasNoCause()
        }
    }

    @Test
    fun givenOwnedAndBorrowedClients__whenSpringDestroysAdapters__thenOnlyOwnedClientClosesOnce() {
        val owned = mock(HttpClient::class.java)
        val borrowed = mock(HttpClient::class.java)
        val ownedAdapter = ApmsHttpClient(BASE, ObjectMapper(), owned, true)
        val borrowedAdapter = ApmsHttpClient(BASE, ObjectMapper(), borrowed)
        GenericApplicationContext().use { context ->
            context.registerBean(
                "ownedAdapter",
                ApmsHttpClient::class.java,
                Supplier { ownedAdapter },
                BeanDefinitionCustomizer { definition -> (definition as AbstractBeanDefinition).setDestroyMethodName("close") },
            )
            context.registerBean(
                "borrowedAdapter",
                ApmsHttpClient::class.java,
                Supplier { borrowedAdapter },
                BeanDefinitionCustomizer { definition -> (definition as AbstractBeanDefinition).setDestroyMethodName("close") },
            )
            context.refresh()
        }
        ownedAdapter.close()
        borrowedAdapter.close()
        verify(owned).close()
        verifyNoInteractions(borrowed)
        assertUnavailable { fetch(ownedAdapter, "fixture-key", 1000) }
        assertUnavailable { fetch(borrowedAdapter, "fixture-key", 1000) }
    }

    @Test
    fun givenOriginalArguments__whenFetched__thenUseExactPathQueriesTimeoutAndTypedDtoWithoutAnotherCall() {
        val client = mock(HttpClient::class.java)
        stub(client, CompletableFuture.completedFuture(response(200, EMPTY_PAGE)))
        val transport = ApmsHttpClient(BASE, ObjectMapper(), client)
        val result = transport.getAbandonmentAnimals(
            "fixture+key/", 1, 1000,
            "20261001", "20261021", "417000", "notice", "json", "20261002", "20261020",
        )
        assertThat(result!!.response!!.body!!.totalCount).isEqualTo("0")
        val request: ArgumentCaptor<HttpRequest> = ArgumentCaptor.forClass(HttpRequest::class.java)
        verify(client).sendAsync(capture(request), anyValue<HttpResponse.BodyHandler<ByteArray>>())
        assertThat(request.value.uri().path).isEqualTo("/openapi/abandonmentPublic_v2")
        assertThat(request.value.timeout()).contains(Duration.ofSeconds(5))
        assertThat(request.value.method()).isEqualTo("GET")
        val query = LinkedHashMap<String, String>()
        for (part in request.value.uri().rawQuery.split("&")) {
            val parts = part.split("=", limit = 2)
            check(query.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8)) == null) {
                "Duplicate key " + parts[0]
            }
        }
        assertThat(query).containsEntry("serviceKey", "fixture+key/").containsEntry("pageNo", "1")
            .containsEntry("numOfRows", "1000").containsEntry("bgnde", "20261001")
            .containsEntry("endde", "20261021").containsEntry("upkind", "417000")
            .containsEntry("state", "notice").containsEntry("_type", "json")
            .containsEntry("bgupd", "20261002").containsEntry("enupd", "20261020").hasSize(10)
    }

    @Test
    fun givenBlankKeyOrInvalidRowLimit__whenFetched__thenRejectBeforeHttp() {
        val client = mock(HttpClient::class.java)
        val transport = ApmsHttpClient(BASE, ObjectMapper(), client)
        assertUnavailable { fetch(transport, " ", 1000) }
        assertUnavailable { fetch(transport, "fixture-key", 1001) }
        assertUnavailable { fetch(transport, "fixture-key", 0) }
        verifyNoInteractions(client)
    }

    @Test
    fun givenProviderRedirectOrInvalidBody__whenFetched__thenNoRetryAndNoBodyOrCauseInException() {
        val replies = listOf(
            response(302, "secret response"),
            response(503, "secret response"),
            response(200, "not-json serviceKey=fixture-secret"),
        )
        for (reply in replies) {
            val client = mock(HttpClient::class.java)
            stub(client, CompletableFuture.completedFuture(reply))
            assertUnavailable { fetch(ApmsHttpClient(BASE, ObjectMapper(), client), "fixture-secret", 1000) }
            verify(client).sendAsync(anyNonNull<HttpRequest>(), anyValue<HttpResponse.BodyHandler<ByteArray>>())
        }
    }

    @Test
    fun givenHttpFailureContainingUriAndKey__whenFetched__thenDiscardOriginalException() {
        val client = mock(HttpClient::class.java)
        val failure = CompletableFuture<HttpResponse<ByteArray>>()
        failure.completeExceptionally(IllegalStateException("https://provider.example.test?serviceKey=fixture-secret"))
        stub(client, failure)
        assertUnavailable { fetch(ApmsHttpClient(BASE, ObjectMapper(), client), "fixture-secret", 1000) }
    }

    @Test
    fun givenBodyWouldExceedLimit__whenBufferArrives__thenCancelBeforeCopyingBuffer() {
        val subscriber = ApmsHttpClient.LimitedBodySubscriber()
        val subscription = mock(Flow.Subscription::class.java)
        subscriber.onSubscribe(subscription)
        val oversized = ByteBuffer.wrap(ByteArray(ApmsHttpClient.MAX_BODY_BYTES + 1))
        subscriber.onNext(mutableListOf(oversized))
        verify(subscription).cancel()
        assertThat(oversized.position()).isZero()
        assertThatThrownBy { subscriber.getBody().toCompletableFuture().join() }
            .hasCauseInstanceOf(ApmsHttpClient.Unavailable::class.java)
    }

    @Test
    fun givenInterruptedWait__whenFetched__thenCancelFutureAndPreserveInterruptWithoutSecrets() {
        val client = mock(HttpClient::class.java)
        val pending = CompletableFuture<HttpResponse<ByteArray>>()
        stub(client, pending)
        Thread.currentThread().interrupt()
        try {
            assertUnavailable { fetch(ApmsHttpClient(BASE, ObjectMapper(), client), "fixture-secret", 1000) }
            assertThat(pending.isCancelled).isTrue()
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun givenUnfinishedResponseBody__whenSixSecondBoundaryExpires__thenCancelWholeRequest() {
        val client = mock(HttpClient::class.java)
        val pending = CompletableFuture<HttpResponse<ByteArray>>()
        stub(client, pending)
        assertUnavailable { fetch(ApmsHttpClient(BASE, ObjectMapper(), client), "fixture-key", 1000) }
        assertThat(pending.isCancelled).isTrue()
    }

    private fun fetch(transport: ApmsHttpClient, key: String, rows: Int) {
        transport.getAbandonmentAnimals(key, 1, rows, "20261001", "20261021", null, null, "json", null, null)
    }

    private fun assertUnavailable(action: () -> Unit) {
        assertThatThrownBy { action() }
            .isInstanceOf(ApmsHttpClient.Unavailable::class.java)
            .hasMessage("APMS request unavailable")
            .hasNoCause()
    }

    private fun stub(client: HttpClient, response: CompletableFuture<HttpResponse<ByteArray>>) {
        whenever(client.sendAsync(anyNonNull<HttpRequest>(), anyValue<HttpResponse.BodyHandler<ByteArray>>()))
            .thenReturn(response)
    }

    @Suppress("UNCHECKED_CAST")
    private fun response(status: Int, body: String): HttpResponse<ByteArray> {
        val response = mock(HttpResponse::class.java) as HttpResponse<ByteArray>
        whenever(response.statusCode()).thenReturn(status)
        whenever(response.body()).thenReturn(body.toByteArray(StandardCharsets.UTF_8))
        return response
    }

    companion object {
        private val BASE: URI = URI.create("https://provider.example.test/openapi")
        private const val EMPTY_PAGE = "{\"response\":{\"header\":{\"resultCode\":\"00\"}," +
            "\"body\":{\"pageNo\":\"1\",\"numOfRows\":\"1000\",\"totalCount\":\"0\",\"items\":{\"item\":[]}}}}"
    }
}
