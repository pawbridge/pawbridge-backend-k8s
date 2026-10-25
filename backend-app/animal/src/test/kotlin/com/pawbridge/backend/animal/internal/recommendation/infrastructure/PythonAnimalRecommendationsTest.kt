package com.pawbridge.backend.animal.internal.recommendation.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.recommendation.application.RecommendationUnavailableException
import feign.Client
import feign.Request
import feign.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.io.IOException
import java.nio.charset.StandardCharsets

class PythonAnimalRecommendationsTest {
    private val transport: Client = mock(Client::class.java)
    private val client = PythonAnimalRecommendations(
        "http://synthetic.invalid:1234",
        "synthetic-test-key",
        ObjectMapper(),
        transport,
    )

    @Test
    fun givenOriginalPythonContract__whenRecommending__thenSendOnlyGetSpeciesAndKeyWithOriginalTimeouts() {
        `when`(transport.execute(any(), any())).thenAnswer { call -> response(call.getArgument(0), 200, "[9,4]") }

        assertThat(client.recommend(73, "DOG")).containsExactly(9L, 4L)

        val request = ArgumentCaptor.forClass(Request::class.java)
        val options = ArgumentCaptor.forClass(Request.Options::class.java)
        verify(transport).execute(request.capture(), options.capture())
        assertThat(request.value.httpMethod()).isEqualTo(Request.HttpMethod.GET)
        assertThat(request.value.url()).isEqualTo("http://synthetic.invalid:1234/internal/animals/73/similar?species=DOG")
        assertThat(request.value.headers()["X-Internal-Api-Key"]).containsExactly("synthetic-test-key")
        assertThat(request.value.body()).isNull()
        assertThat(options.value.connectTimeoutMillis()).isEqualTo(3000)
        assertThat(options.value.readTimeoutMillis()).isEqualTo(45000)
        assertThat(options.value.isFollowRedirects).isFalse()
    }

    @Test
    fun givenTransportFailure__whenRecommending__thenDoNotRetryOrRetainCredentialBearingFailure() {
        `when`(transport.execute(any(), any())).thenThrow(IOException("synthetic-test-key private body"))

        assertThatThrownBy { client.recommend(73, "CAT") }
            .isInstanceOf(RecommendationUnavailableException::class.java)
            .hasNoCause()
            .hasMessage("추천 자료를 준비 중입니다. 잠시 후 다시 시도해 주세요")
        verify(transport, times(1)).execute(any(), any())
    }

    @ParameterizedTest
    @ValueSource(ints = [307, 401, 404, 503])
    fun givenUnsuccessfulUpstream__whenRecommending__thenDoNotRetryOrReturnEmpty(status: Int) {
        `when`(transport.execute(any(), any()))
            .thenAnswer { call -> response(call.getArgument(0), status, "synthetic private body") }

        assertThatThrownBy { client.recommend(73, "DOG") }
            .isInstanceOf(RecommendationUnavailableException::class.java)
            .hasNoCause()
        verify(transport, times(1)).execute(any(), any())
    }

    @Test
    fun givenMissingInternalKey__whenRecommending__thenNeverSendRequest() {
        val noKey = PythonAnimalRecommendations("http://synthetic.invalid", " \t", ObjectMapper(), transport)

        assertThatThrownBy { noKey.recommend(1, "DOG") }
            .isInstanceOf(RecommendationUnavailableException::class.java)
        verifyNoInteractions(transport)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "not-json", "[9223372036854775808]"])
    fun givenUndecodableReply__whenRecommending__thenFailUnavailable(body: String) {
        `when`(transport.execute(any(), any())).thenAnswer { call -> response(call.getArgument(0), 200, body) }

        assertThatThrownBy { client.recommend(73, "DOG") }
            .isInstanceOf(RecommendationUnavailableException::class.java)
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
