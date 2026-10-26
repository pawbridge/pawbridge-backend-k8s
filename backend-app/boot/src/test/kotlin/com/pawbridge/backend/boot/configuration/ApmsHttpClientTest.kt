package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.context.support.GenericApplicationContext
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpHeaders
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Optional
import javax.net.ssl.SSLSession
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.function.Supplier

class ApmsHttpClientTest {
    @Test
    fun `명시한 제공자 기준 URL이 주어졌을 때__운영 전송을 만들면__HTTPS 제공자 URL만 받는다`() {
        // given, when
        ApmsHttpClient(BASE, ObjectMapper()).use { transport ->
            // then
            assertThat(transport).isNotNull()
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
    fun `소유한 클라이언트와 빌린 클라이언트가 있을 때__스프링이 어댑터를 파괴하면__소유한 클라이언트만 한 번 닫는다`() {
        // given
        val owned = mockk<HttpClient>()
        val borrowed = mockk<HttpClient>()
        val ownedAdapter = ApmsHttpClient(BASE, ObjectMapper(), owned, true)
        val borrowedAdapter = ApmsHttpClient(BASE, ObjectMapper(), borrowed)

        justRun { owned.close() }

        // when
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

        // then
        verify(exactly = 1) { owned.close() }
        confirmVerified(borrowed)
        assertUnavailable { fetch(ownedAdapter, "fixture-key", 1000) }
        assertUnavailable { fetch(borrowedAdapter, "fixture-key", 1000) }
    }

    @Test
    fun `원본 인자가 주어졌을 때__조회하면__정확한 경로와 질의와 타임아웃과 타입 DTO를 쓰고 다시 호출하지 않는다`() {
        // given
        val client = mockk<HttpClient>()
        val transport = ApmsHttpClient(BASE, ObjectMapper(), client)

        every { client.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
            CompletableFuture.completedFuture(response(200, EMPTY_PAGE))

        // when
        val result = transport.getAbandonmentAnimals(
            "fixture+key/", 1, 1000,
            "20261001", "20261021", "417000", "notice", "json", "20261002", "20261020",
        )

        // then
        val request = slot<HttpRequest>()
        verify(exactly = 1) { client.sendAsync(capture(request), any<HttpResponse.BodyHandler<ByteArray>>()) }
        assertThat(result!!.response!!.body!!.totalCount).isEqualTo("0")
        assertThat(request.captured.uri().path).isEqualTo("/openapi/abandonmentPublic_v2")
        assertThat(request.captured.timeout()).contains(Duration.ofSeconds(5))
        assertThat(request.captured.method()).isEqualTo("GET")
        val query = LinkedHashMap<String, String>()
        for (part in request.captured.uri().rawQuery.split("&")) {
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
    fun `공백 키나 잘못된 행 수가 주어졌을 때__조회하면__HTTP 전에 거절한다`() {
        // given
        val client = mockk<HttpClient>()
        val transport = ApmsHttpClient(BASE, ObjectMapper(), client)

        // when, then
        assertUnavailable { fetch(transport, " ", 1000) }
        assertUnavailable { fetch(transport, "fixture-key", 1001) }
        assertUnavailable { fetch(transport, "fixture-key", 0) }
        confirmVerified(client)
    }

    @Test
    fun `제공자가 리다이렉트나 잘못된 본문을 줄 때__조회하면__재시도 없이 본문과 원인을 예외에 담지 않는다`() {
        // given
        val replies = listOf(
            response(302, "secret response"),
            response(503, "secret response"),
            response(200, "not-json serviceKey=fixture-secret"),
        )
        for (reply in replies) {
            val client = mockk<HttpClient>()

            every { client.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
                CompletableFuture.completedFuture(reply)

            // when, then
            assertUnavailable { fetch(ApmsHttpClient(BASE, ObjectMapper(), client), "fixture-secret", 1000) }
            verify(exactly = 1) { client.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<ByteArray>>()) }
        }
    }

    @Test
    fun `HTTP 실패에 URI와 키가 들어 있을 때__조회하면__원래 예외를 버린다`() {
        // given
        val client = mockk<HttpClient>()
        val failure = CompletableFuture<HttpResponse<ByteArray>>()
        failure.completeExceptionally(IllegalStateException("https://provider.example.test?serviceKey=fixture-secret"))

        every { client.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns failure

        // when, then
        assertUnavailable { fetch(ApmsHttpClient(BASE, ObjectMapper(), client), "fixture-secret", 1000) }
    }

    @Test
    fun `본문이 한도를 넘을 때__버퍼가 도착하면__복사 전에 취소한다`() {
        // given
        val subscriber = ApmsHttpClient.LimitedBodySubscriber()
        val subscription = mockk<Flow.Subscription>()
        val oversized = ByteBuffer.wrap(ByteArray(ApmsHttpClient.MAX_BODY_BYTES + 1))

        justRun { subscription.request(any()) }
        justRun { subscription.cancel() }

        // when
        subscriber.onSubscribe(subscription)
        subscriber.onNext(mutableListOf(oversized))

        // then
        verify(exactly = 1) { subscription.cancel() }
        assertThat(oversized.position()).isZero()
        assertThatThrownBy { subscriber.getBody().toCompletableFuture().join() }
            .hasCauseInstanceOf(ApmsHttpClient.Unavailable::class.java)
    }

    @Test
    fun `대기 중 인터럽트되었을 때__조회하면__퓨처를 취소하고 비밀 없이 인터럽트를 보존한다`() {
        // given
        val client = mockk<HttpClient>()
        val pending = CompletableFuture<HttpResponse<ByteArray>>()

        every { client.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns pending

        Thread.currentThread().interrupt()
        try {
            // when, then
            assertUnavailable { fetch(ApmsHttpClient(BASE, ObjectMapper(), client), "fixture-secret", 1000) }
            assertThat(pending.isCancelled).isTrue()
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `응답 본문이 끝나지 않을 때__6초 경계가 지나면__요청 전체를 취소한다`() {
        // given
        val client = mockk<HttpClient>()
        val pending = CompletableFuture<HttpResponse<ByteArray>>()

        every { client.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns pending

        // when, then
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

    /** Data builder: only the status code and the body are meaningful, any other accessor fails loudly like a strict mock. */
    private fun response(status: Int, body: String): HttpResponse<ByteArray> {
        return object : HttpResponse<ByteArray> {
            override fun statusCode(): Int = status

            override fun body(): ByteArray = body.toByteArray(StandardCharsets.UTF_8)

            override fun request(): HttpRequest = unsupported()

            override fun previousResponse(): Optional<HttpResponse<ByteArray>> = unsupported()

            override fun headers(): HttpHeaders = unsupported()

            override fun sslSession(): Optional<SSLSession> = unsupported()

            override fun uri(): URI = unsupported()

            override fun version(): HttpClient.Version = unsupported()

            private fun <T> unsupported(): T {
                throw UnsupportedOperationException("not stubbed")
            }
        }
    }

    companion object {
        private val BASE: URI = URI.create("https://provider.example.test/openapi")
        private const val EMPTY_PAGE = "{\"response\":{\"header\":{\"resultCode\":\"00\"}," +
            "\"body\":{\"pageNo\":\"1\",\"numOfRows\":\"1000\",\"totalCount\":\"0\",\"items\":{\"item\":[]}}}}"
    }
}
