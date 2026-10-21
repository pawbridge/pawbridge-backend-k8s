package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.apms.infrastructure.client.ApmsApiClient;
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal;
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsRootResponse;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** APMS transport only. Snapshot retains its existing header checks and bounded recovery. */
final class ApmsHttpClient implements ApmsApiClient, AutoCloseable {
    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private final HttpClient client;
    private final URI base;
    private final ObjectMapper mapper;
    private final JavaType responseType;
    private final boolean ownsClient;
    private volatile boolean closed;

    ApmsHttpClient(URI base, ObjectMapper mapper) {
        this(base, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build(), true);
    }

    /** Borrowed test seam: closing this adapter never closes the caller's HttpClient. */
    ApmsHttpClient(URI base, ObjectMapper mapper, HttpClient client) {
        this(base, mapper, client, false);
    }

    /** Explicit ownership seam for offline close regression tests. */
    ApmsHttpClient(URI base, ObjectMapper mapper, HttpClient client, boolean ownsClient) {
        this.base = baseUri(base == null ? null : base.toString());
        this.mapper = mapper;
        this.client = client;
        this.ownsClient = ownsClient;
        this.responseType = mapper.getTypeFactory().constructParametricType(ApmsRootResponse.class, ApmsAnimal.class);
    }

    static URI baseUri(String configured) {
        try {
            if (configured == null || configured.isBlank()) throw new IllegalArgumentException();
            URI uri = URI.create(configured);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getRawAuthority() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("APMS base URL must be an explicit HTTPS provider URL");
        }
    }

    @Override
    public ApmsRootResponse<ApmsAnimal> getAbandonmentAnimals(String serviceKey, int pageNo, int numOfRows,
            String bgnde, String endde, String upkind, String state, String type,
            String updatedBeginDate, String updatedEndDate) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            if (closed || serviceKey == null || serviceKey.isBlank() || serviceKey.chars().anyMatch(Character::isISOControl)
                    || pageNo < 1 || numOfRows < 1 || numOfRows > 1000)
                throw new Unavailable();
            StringBuilder query = new StringBuilder();
            parameter(query, "serviceKey", serviceKey);
            parameter(query, "pageNo", Integer.toString(pageNo));
            parameter(query, "numOfRows", Integer.toString(numOfRows));
            parameter(query, "bgnde", bgnde);
            parameter(query, "endde", endde);
            parameter(query, "upkind", upkind);
            parameter(query, "state", state);
            parameter(query, "_type", type == null ? "json" : type);
            parameter(query, "bgupd", updatedBeginDate);
            parameter(query, "enupd", updatedEndDate);
            String prefix = base.toASCIIString();
            while (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
            URI target = URI.create(prefix + "/abandonmentPublic_v2?" + query);
            var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json").GET().build();
            pending = client.sendAsync(request, ignored -> new LimitedBodySubscriber());
            var response = pending.get(6, TimeUnit.SECONDS);
            if (response.statusCode() != 200 || response.body() == null || response.body().length > MAX_BODY_BYTES)
                throw new Unavailable();
            return mapper.readValue(response.body(), responseType);
        } catch (InterruptedException interrupted) {
            if (pending != null) pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new Unavailable();
        } catch (Exception unavailable) {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            throw new Unavailable();
        }
    }

    private static void parameter(StringBuilder query, String key, String value) {
        if (value == null) return;
        if (!query.isEmpty()) query.append('&');
        query.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (ownsClient) {
            try { client.close(); }
            catch (RuntimeException failure) { throw new Unavailable(); }
        }
    }

    static final class Unavailable extends IllegalStateException {
        Unavailable() { super("APMS request unavailable", null); }
    }

    /** Cancel before copying a buffer that would exceed the body limit. */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null) { incoming.cancel(); return; }
            subscription = incoming;
            incoming.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BODY_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new Unavailable());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable ignored) { result.completeExceptionally(new Unavailable()); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
