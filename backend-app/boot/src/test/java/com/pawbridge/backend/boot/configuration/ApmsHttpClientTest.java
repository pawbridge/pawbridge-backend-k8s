package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.support.GenericApplicationContext;

class ApmsHttpClientTest {
    private static final URI BASE = URI.create("https://provider.example.test/openapi");
    private static final String EMPTY_PAGE = "{\"response\":{\"header\":{\"resultCode\":\"00\"},"
            + "\"body\":{\"pageNo\":\"1\",\"numOfRows\":\"1000\",\"totalCount\":\"0\",\"items\":{\"item\":[]}}}}";

    @Test void givenExplicitProviderBase__whenProductionTransportIsCreated__thenHttpsAndFiniteConnectWithoutRedirects() {
        try (var transport = new ApmsHttpClient(BASE, new ObjectMapper())) {
            var client = (HttpClient) ReflectionTestUtils.getField(transport, "client");
            assertThat(client.connectTimeout()).contains(Duration.ofSeconds(2));
            assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
            assertThat(ReflectionTestUtils.getField(transport, "ownsClient")).isEqualTo(true);
        }
        for (String invalid : new String[] {"", "http://localhost:8080", "https://user:password@example.test",
                "https://example.test?serviceKey=fixture", "https://example.test#fragment", "https:///missing-authority"}) {
            assertThatThrownBy(() -> ApmsHttpClient.baseUri(invalid)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("APMS base URL must be an explicit HTTPS provider URL").hasNoCause();
        }
    }

    @Test void givenOwnedAndBorrowedClients__whenSpringDestroysAdapters__thenOnlyOwnedClientClosesOnce() {
        var owned = mock(HttpClient.class);
        var borrowed = mock(HttpClient.class);
        var ownedAdapter = new ApmsHttpClient(BASE, new ObjectMapper(), owned, true);
        var borrowedAdapter = new ApmsHttpClient(BASE, new ObjectMapper(), borrowed);
        try (var context = new GenericApplicationContext()) {
            context.registerBean("ownedAdapter", ApmsHttpClient.class, () -> ownedAdapter,
                    definition -> ((org.springframework.beans.factory.support.AbstractBeanDefinition) definition)
                            .setDestroyMethodName("close"));
            context.registerBean("borrowedAdapter", ApmsHttpClient.class, () -> borrowedAdapter,
                    definition -> ((org.springframework.beans.factory.support.AbstractBeanDefinition) definition)
                            .setDestroyMethodName("close"));
            context.refresh();
        }
        ownedAdapter.close();
        borrowedAdapter.close();
        verify(owned).close();
        verifyNoInteractions(borrowed);
        assertUnavailable(() -> fetch(ownedAdapter, "fixture-key", 1000));
        assertUnavailable(() -> fetch(borrowedAdapter, "fixture-key", 1000));
    }

    @Test void givenOriginalArguments__whenFetched__thenUseExactPathQueriesTimeoutAndTypedDtoWithoutAnotherCall() throws Exception {
        var client = mock(HttpClient.class);
        stub(client, CompletableFuture.completedFuture(response(200, EMPTY_PAGE)));
        var transport = new ApmsHttpClient(BASE, new ObjectMapper(), client);
        var result = transport.getAbandonmentAnimals("fixture+key/", 1, 1000,
                "20261001", "20261021", "417000", "notice", "json", "20261002", "20261020");
        assertThat(result.getResponse().getBody().getTotalCount()).isEqualTo("0");
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).sendAsync(request.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        assertThat(request.getValue().uri().getPath()).isEqualTo("/openapi/abandonmentPublic_v2");
        assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(5));
        assertThat(request.getValue().method()).isEqualTo("GET");
        var query = Arrays.stream(request.getValue().uri().getRawQuery().split("&"))
                .map(part -> part.split("=", 2)).collect(Collectors.toMap(parts -> parts[0],
                        parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
        assertThat(query).containsEntry("serviceKey", "fixture+key/").containsEntry("pageNo", "1")
                .containsEntry("numOfRows", "1000").containsEntry("bgnde", "20261001")
                .containsEntry("endde", "20261021").containsEntry("upkind", "417000")
                .containsEntry("state", "notice").containsEntry("_type", "json")
                .containsEntry("bgupd", "20261002").containsEntry("enupd", "20261020").hasSize(10);
    }

    @Test void givenBlankKeyOrInvalidRowLimit__whenFetched__thenRejectBeforeHttp() {
        var client = mock(HttpClient.class);
        var transport = new ApmsHttpClient(BASE, new ObjectMapper(), client);
        assertUnavailable(() -> fetch(transport, " ", 1000));
        assertUnavailable(() -> fetch(transport, "fixture-key", 1001));
        assertUnavailable(() -> fetch(transport, "fixture-key", 0));
        verifyNoInteractions(client);
    }

    @Test void givenProviderRedirectOrInvalidBody__whenFetched__thenNoRetryAndNoBodyOrCauseInException() {
        for (var reply : List.of(response(302, "secret response"), response(503, "secret response"),
                response(200, "not-json serviceKey=fixture-secret"))) {
            var client = mock(HttpClient.class);
            stub(client, CompletableFuture.completedFuture(reply));
            assertUnavailable(() -> fetch(new ApmsHttpClient(BASE, new ObjectMapper(), client), "fixture-secret", 1000));
            verify(client).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        }
    }

    @Test void givenHttpFailureContainingUriAndKey__whenFetched__thenDiscardOriginalException() {
        var client = mock(HttpClient.class);
        var failure = new CompletableFuture<HttpResponse<byte[]>>();
        failure.completeExceptionally(new IllegalStateException("https://provider.example.test?serviceKey=fixture-secret"));
        stub(client, failure);
        assertUnavailable(() -> fetch(new ApmsHttpClient(BASE, new ObjectMapper(), client), "fixture-secret", 1000));
    }

    @Test void givenBodyWouldExceedLimit__whenBufferArrives__thenCancelBeforeCopyingBuffer() {
        var subscriber = new ApmsHttpClient.LimitedBodySubscriber();
        var subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        var oversized = ByteBuffer.wrap(new byte[ApmsHttpClient.MAX_BODY_BYTES + 1]);
        subscriber.onNext(List.of(oversized));
        verify(subscription).cancel();
        assertThat(oversized.position()).isZero();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
                .hasCauseInstanceOf(ApmsHttpClient.Unavailable.class);
    }

    @Test void givenInterruptedWait__whenFetched__thenCancelFutureAndPreserveInterruptWithoutSecrets() {
        var client = mock(HttpClient.class);
        var pending = new CompletableFuture<HttpResponse<byte[]>>();
        stub(client, pending);
        Thread.currentThread().interrupt();
        try {
            assertUnavailable(() -> fetch(new ApmsHttpClient(BASE, new ObjectMapper(), client), "fixture-secret", 1000));
            assertThat(pending.isCancelled()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void givenUnfinishedResponseBody__whenSixSecondBoundaryExpires__thenCancelWholeRequest() {
        var client = mock(HttpClient.class);
        var pending = new CompletableFuture<HttpResponse<byte[]>>();
        stub(client, pending);
        assertUnavailable(() -> fetch(new ApmsHttpClient(BASE, new ObjectMapper(), client), "fixture-key", 1000));
        assertThat(pending.isCancelled()).isTrue();
    }

    private static void fetch(ApmsHttpClient transport, String key, int rows) {
        transport.getAbandonmentAnimals(key, 1, rows, "20261001", "20261021", null, null, "json", null, null);
    }

    private static void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ApmsHttpClient.Unavailable.class)
                .hasMessage("APMS request unavailable").hasNoCause();
    }

    private static void stub(HttpClient client, CompletableFuture<HttpResponse<byte[]>> response) {
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }
}
