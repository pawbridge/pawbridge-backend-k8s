package com.pawbridge.apigateway.filter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Bounded public upload admission; counters apply to this gateway instance, not individual IPs. */
@Component
public class LostSearchLimitsGatewayFilterFactory extends AbstractGatewayFilterFactory<LostSearchLimitsGatewayFilterFactory.Config> {
    private final int maxBytes;
    private final Duration timeout;
    private final int requestsPerMinute;
    private final Semaphore active;
    private final ArrayDeque<Long> starts = new ArrayDeque<>();

    public LostSearchLimitsGatewayFilterFactory(
            @Value("${lost-search.max-request-bytes:6291456}") int maxBytes,
            @Value("${lost-search.max-concurrent:2}") int maxConcurrent,
            @Value("${lost-search.requests-per-minute:30}") int requestsPerMinute,
            @Value("${lost-search.timeout-seconds:50}") int timeoutSeconds) {
        super(Config.class);
        if (maxBytes <= 0 || maxConcurrent <= 0 || requestsPerMinute <= 0 || timeoutSeconds <= 0) {
            throw new IllegalArgumentException("Lost search limits must be positive");
        }
        this.maxBytes = maxBytes;
        this.active = new Semaphore(maxConcurrent);
        this.requestsPerMinute = requestsPerMinute;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> Mono.defer(() -> {
            var headers = exchange.getRequest().getHeaders();
            if (headers.getContentLength() > maxBytes) return reject(exchange, HttpStatus.PAYLOAD_TOO_LARGE);
            MediaType type;
            try { type = headers.getContentType(); }
            catch (IllegalArgumentException ex) { return reject(exchange, HttpStatus.UNSUPPORTED_MEDIA_TYPE); }
            if (type == null || !MediaType.MULTIPART_FORM_DATA.isCompatibleWith(type)) {
                return reject(exchange, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            }
            if (!active.tryAcquire()) return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE);
            if (!admit()) {
                active.release();
                return reject(exchange, HttpStatus.TOO_MANY_REQUESTS);
            }
            // Size is enforced from actual buffers even without a truthful Content-Length.
            return DataBufferUtils.join(exchange.getRequest().getBody(), maxBytes)
                    .map(buffer -> {
                        try {
                            byte[] bytes = new byte[buffer.readableByteCount()];
                            buffer.read(bytes);
                            return bytes;
                        } finally { DataBufferUtils.release(buffer); }
                    })
                    .defaultIfEmpty(new byte[0])
                    .flatMap(bytes -> {
                        if (bytes.length == 0) return reject(exchange, HttpStatus.BAD_REQUEST);
                        var request = new ServerHttpRequestDecorator(exchange.getRequest()) {
                            @Override public HttpHeaders getHeaders() {
                                var result = new HttpHeaders();
                                result.putAll(super.getHeaders());
                                result.remove(HttpHeaders.TRANSFER_ENCODING);
                                result.remove(HttpHeaders.AUTHORIZATION);
                                result.remove("X-User-Id");
                                result.remove("X-Internal-Api-Key");
                                result.setContentLength(bytes.length);
                                return result;
                            }
                            @Override public Flux<DataBuffer> getBody() {
                                return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(bytes)));
                            }
                        };
                        return chain.filter(exchange.mutate().request(request).build());
                    })
                    .timeout(timeout)
                    .onErrorResume(DataBufferLimitException.class, ex -> reject(exchange, HttpStatus.PAYLOAD_TOO_LARGE))
                    .onErrorResume(TimeoutException.class, ex -> reject(exchange, HttpStatus.GATEWAY_TIMEOUT))
                    .doFinally(signal -> active.release());
        });
    }

    private synchronized boolean admit() {
        long now = System.nanoTime();
        long minute = Duration.ofMinutes(1).toNanos();
        while (!starts.isEmpty() && now - starts.peekFirst() >= minute) starts.removeFirst();
        if (starts.size() >= requestsPerMinute) return false;
        starts.addLast(now);
        return true;
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        var response = exchange.getResponse();
        if (response.isCommitted()) return response.setComplete();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (status == HttpStatus.TOO_MANY_REQUESTS) response.getHeaders().set("Retry-After", "60");
        if (status == HttpStatus.SERVICE_UNAVAILABLE) response.getHeaders().set("Retry-After", "3");
        byte[] body = ("{\"message\":\"" + switch (status) {
            case PAYLOAD_TOO_LARGE -> "사진 크기 제한을 초과했습니다";
            case TOO_MANY_REQUESTS -> "검색 요청이 많습니다. 잠시 후 다시 시도해 주세요";
            case GATEWAY_TIMEOUT -> "검색 시간이 초과됐습니다";
            case UNSUPPORTED_MEDIA_TYPE -> "사진 업로드 형식을 확인해 주세요";
            case BAD_REQUEST -> "사진을 선택해 주세요";
            default -> "잠시 후 다시 시도해 주세요";
        } + "\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    public static class Config {}
}
