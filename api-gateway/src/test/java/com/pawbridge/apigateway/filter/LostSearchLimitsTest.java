package com.pawbridge.apigateway.filter;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.*;

class LostSearchLimitsTest {
    private GatewayFilter filter(int maxBytes, int concurrent, int perMinute, int seconds) {
        return new LostSearchLimitsGatewayFilterFactory(maxBytes, concurrent, perMinute, seconds)
                .apply(new LostSearchLimitsGatewayFilterFactory.Config());
    }
    private MockServerWebExchange exchange(String body) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/animals/lost-candidates")
                .header("Content-Type", "multipart/form-data; boundary=test")
                .header("X-Internal-Api-Key", "untrusted").header("Authorization", "untrusted").body(body));
    }
    private GatewayFilterChain ok = exchange -> {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return exchange.getResponse().setComplete();
    };

    @Test
    void givenChunkedUpload__whenForwarded__thenPreserveBytesAndRemoveUntrustedIdentity() {
        var exchange = exchange("photo-body");
        filter(100, 1, 30, 5).filter(exchange, downstream -> {
            assertThat(downstream.getRequest().getHeaders().getFirst("X-Internal-Api-Key")).isNull();
            assertThat(downstream.getRequest().getHeaders().getFirst("Authorization")).isNull();
            assertThat(downstream.getRequest().getHeaders().getContentLength()).isEqualTo(10);
            return DataBufferUtils.join(downstream.getRequest().getBody()).doOnNext(buffer -> {
                try {
                    byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes);
                    assertThat(new String(bytes)).isEqualTo("photo-body");
                } finally { DataBufferUtils.release(buffer); }
            }).then();
        }).block();
    }

    @Test
    void givenOversizedStreamWithoutLength__whenRead__thenReturn413AndReleasePermit() {
        var filter = filter(5, 1, 30, 5);
        var request = MockServerHttpRequest.post("/api/v1/animals/lost-candidates")
                .header("Content-Type", "multipart/form-data; boundary=test")
                .body(Flux.just(new DefaultDataBufferFactory().wrap(new byte[3]), new DefaultDataBufferFactory().wrap(new byte[3])));
        var exchange = MockServerWebExchange.from(request);
        filter.filter(exchange, ignored -> { throw new AssertionError("must not forward oversized upload"); }).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        var next = exchange("ok"); filter.filter(next, ok).block();
        assertThat(next.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void givenLargeDeclaredLength__whenReceived__thenRejectWithoutReading() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/animals/lost-candidates")
                .header("Content-Length", "101").body(Flux.error(new AssertionError("must not read"))));
        filter(100, 1, 30, 5).filter(exchange, ok).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void givenNonMultipart__whenReceived__thenReject() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/animals/lost-candidates").body("{}"));
        filter(100, 1, 30, 5).filter(exchange, ok).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void givenRequestInProgress__whenAnotherArrives__thenRejectAndReleaseOnCancel() {
        var filter = filter(100, 1, 30, 5);
        var running = filter.filter(exchange("ok"), ignored -> Mono.never()).subscribe();
        try {
            var second = exchange("ok"); filter.filter(second, ok).block();
            assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(second.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("3");
        } finally { running.dispose(); }
        var third = exchange("ok"); filter.filter(third, ok).block();
        assertThat(third.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void givenMinuteBudgetUsed__whenRetrying__then429EvenWithDifferentSpoofedHeader() {
        var filter = filter(100, 1, 1, 5);
        filter.filter(exchange("ok"), ok).block();
        var second = exchange("ok"); filter.filter(second, ok).block();
        assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(second.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }

    @Test
    void givenStalledDownstream__whenDeadlinePasses__then504AndPermitReleased() {
        var filter = filter(100, 1, 30, 1);
        var exchange = exchange("ok");
        filter.filter(exchange, ignored -> Mono.never()).block(Duration.ofSeconds(3));
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        var next = exchange("ok"); filter.filter(next, ok).block();
        assertThat(next.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
