package com.pawbridge.backend.animal.internal.recommendation.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.recommendation.application.RecommendationUnavailableException
import feign.Client
import feign.Request
import feign.Response
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.nio.charset.StandardCharsets

class PythonAnimalRecommendationsTest {
    private val transport: Client = mockk<Client>()
    private val client = PythonAnimalRecommendations(
        "http://synthetic.invalid:1234",
        "synthetic-test-key",
        ObjectMapper(),
        transport,
    )

    @Test
    fun `원래 파이썬 계약일 때__추천하면__GET과 종과 키만 원래 제한 시간으로 보낸다`() {
        // given
        every { transport.execute(any(), any()) } answers { response(firstArg(), 200, "[9,4]") }

        // when
        val result = client.recommend(73, "DOG")

        // then
        val request = slot<Request>()
        val options = slot<Request.Options>()
        assertThat(result).containsExactly(9L, 4L)
        verify(exactly = 1) { transport.execute(capture(request), capture(options)) }
        assertThat(request.captured.httpMethod()).isEqualTo(Request.HttpMethod.GET)
        assertThat(request.captured.url()).isEqualTo("http://synthetic.invalid:1234/internal/animals/73/similar?species=DOG")
        assertThat(request.captured.headers()["X-Internal-Api-Key"]).containsExactly("synthetic-test-key")
        assertThat(request.captured.body()).isNull()
        assertThat(options.captured.connectTimeoutMillis()).isEqualTo(3000)
        assertThat(options.captured.readTimeoutMillis()).isEqualTo(45000)
        assertThat(options.captured.isFollowRedirects).isFalse()
    }

    @Test
    fun `전송이 실패할 때__추천하면__재시도하지 않고 자격 증명이 담긴 실패도 남기지 않는다`() {
        // given
        every { transport.execute(any(), any()) } throws IOException("synthetic-test-key private body")

        // when
        val thrown = assertThatThrownBy { client.recommend(73, "CAT") }

        // then
        thrown.isInstanceOf(RecommendationUnavailableException::class.java)
            .hasNoCause()
            .hasMessage("추천 자료를 준비 중입니다. 잠시 후 다시 시도해 주세요")
        verify(exactly = 1) { transport.execute(any(), any()) }
    }

    @ParameterizedTest
    @ValueSource(ints = [307, 401, 404, 503])
    fun `상류가 성공하지 못했을 때__추천하면__재시도하거나 빈 결과로 돌려주지 않는다`(status: Int) {
        // given
        every { transport.execute(any(), any()) } answers { response(firstArg(), status, "synthetic private body") }

        // when
        val thrown = assertThatThrownBy { client.recommend(73, "DOG") }

        // then
        thrown.isInstanceOf(RecommendationUnavailableException::class.java)
            .hasNoCause()
        verify(exactly = 1) { transport.execute(any(), any()) }
    }

    @Test
    fun `내부 키가 비어 있을 때__추천하면__요청을 보내지 않는다`() {
        // given
        val noKey = PythonAnimalRecommendations("http://synthetic.invalid", " \t", ObjectMapper(), transport)

        // when
        val thrown = assertThatThrownBy { noKey.recommend(1, "DOG") }

        // then
        thrown.isInstanceOf(RecommendationUnavailableException::class.java)
        confirmVerified(transport)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "not-json", "[9223372036854775808]"])
    fun `해석할 수 없는 응답일 때__추천하면__이용 불가로 실패한다`(body: String) {
        // given
        every { transport.execute(any(), any()) } answers { response(firstArg(), 200, body) }

        // when
        val thrown = assertThatThrownBy { client.recommend(73, "DOG") }

        // then
        thrown.isInstanceOf(RecommendationUnavailableException::class.java)
            .hasNoCause()
    }

    private fun response(request: Request, status: Int, body: String): Response {
        return Response.builder()
            .request(request)
            .status(status)
            .reason("synthetic")
            .headers(mapOf("Content-Type" to listOf("application/json")))
            .body(body, StandardCharsets.UTF_8)
            .build()
    }
}
