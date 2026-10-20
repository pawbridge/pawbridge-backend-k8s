package com.pawbridge.backend.animal.internal.recommendation.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.recommendation.application.RecommendationUnavailableException;
import feign.Client;
import feign.Request;
import feign.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PythonAnimalRecommendationsTest {
    private final Client transport = mock(Client.class);
    private final PythonAnimalRecommendations client = new PythonAnimalRecommendations(
            "http://synthetic.invalid:1234", "synthetic-test-key", new ObjectMapper(), transport);

    @Test
    void givenOriginalPythonContract__whenRecommending__thenSendOnlyGetSpeciesAndKeyWithOriginalTimeouts() throws Exception {
        when(transport.execute(any(), any())).thenAnswer(call -> response(call.getArgument(0), 200, "[9,4]"));
        assertThat(client.recommend(73, "DOG")).containsExactly(9L, 4L);
        var request = ArgumentCaptor.forClass(Request.class);
        var options = ArgumentCaptor.forClass(Request.Options.class);
        verify(transport).execute(request.capture(), options.capture());
        assertThat(request.getValue().httpMethod()).isEqualTo(Request.HttpMethod.GET);
        assertThat(request.getValue().url()).isEqualTo("http://synthetic.invalid:1234/internal/animals/73/similar?species=DOG");
        assertThat(request.getValue().headers().get("X-Internal-Api-Key")).containsExactly("synthetic-test-key");
        assertThat(request.getValue().body()).isNull();
        assertThat(options.getValue().connectTimeoutMillis()).isEqualTo(3000);
        assertThat(options.getValue().readTimeoutMillis()).isEqualTo(45000);
        assertThat(options.getValue().isFollowRedirects()).isFalse();
    }

    @Test
    void givenTransportFailure__whenRecommending__thenDoNotRetryOrRetainCredentialBearingFailure() throws Exception {
        when(transport.execute(any(), any())).thenThrow(new IOException("synthetic-test-key private body"));
        assertThatThrownBy(() -> client.recommend(73, "CAT")).isInstanceOf(RecommendationUnavailableException.class)
                .hasNoCause().hasMessage("추천 자료를 준비 중입니다. 잠시 후 다시 시도해 주세요");
        verify(transport, times(1)).execute(any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {307, 401, 404, 503})
    void givenUnsuccessfulUpstream__whenRecommending__thenDoNotRetryOrReturnEmpty(int status) throws Exception {
        when(transport.execute(any(), any())).thenAnswer(call -> response(call.getArgument(0), status, "synthetic private body"));
        assertThatThrownBy(() -> client.recommend(73, "DOG")).isInstanceOf(RecommendationUnavailableException.class).hasNoCause();
        verify(transport, times(1)).execute(any(), any());
    }

    @Test
    void givenMissingInternalKey__whenRecommending__thenNeverSendRequest() {
        var noKey = new PythonAnimalRecommendations("http://synthetic.invalid", " \t", new ObjectMapper(), transport);
        assertThatThrownBy(() -> noKey.recommend(1, "DOG")).isInstanceOf(RecommendationUnavailableException.class);
        verifyNoInteractions(transport);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "not-json", "[9223372036854775808]"})
    void givenUndecodableReply__whenRecommending__thenFailUnavailable(String body) throws Exception {
        when(transport.execute(any(), any())).thenAnswer(call -> response(call.getArgument(0), 200, body));
        assertThatThrownBy(() -> client.recommend(73, "DOG")).isInstanceOf(RecommendationUnavailableException.class).hasNoCause();
    }

    private static Response response(Request request, int status, String body) {
        return Response.builder().request(request).status(status).reason("synthetic")
                .headers(Map.of("Content-Type", List.of("application/json")))
                .body(body, StandardCharsets.UTF_8).build();
    }
}
