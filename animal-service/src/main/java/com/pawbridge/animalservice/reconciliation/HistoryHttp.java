package com.pawbridge.animalservice.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Never returns provider error bodies/URLs, which can contain service keys. No redirects or hidden retries. */
final class HistoryHttp {
    private final HttpClient client;
    HistoryHttp() { this(null); }
    HistoryHttp(String certificatePath) {
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (certificatePath != null && !certificatePath.isBlank()) {
            try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(certificatePath))) {
                var certificates = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(input);
                if (certificates.isEmpty()) HistoryPlan.fail("Empty search CA bundle");
                var store = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
                store.load(null, null);
                int index = 0;
                for (var certificate : certificates) store.setCertificateEntry("search-ca-" + index++, certificate);
                var trust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
                trust.init(store);
                var context = javax.net.ssl.SSLContext.getInstance("TLS");
                context.init(null, trust.getTrustManagers(), null);
                builder.sslContext(context);
            } catch (Exception failure) { throw new IllegalStateException("Cannot load search CA bundle"); }
        }
        client = builder.build();
    }
    record Response(int status, JsonNode body) { }
    Response request(String method, String url, Object body, String authorization, boolean allow404) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(40))
                .header("Accept", "application/json");
        if (authorization != null && !authorization.isBlank()) builder.header("Authorization", authorization);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method,
                HttpRequest.BodyPublishers.ofByteArray(HistoryPlan.JSON.writeValueAsBytes(body)));
        HttpResponse<byte[]> response;
        try { response = client.send(builder.build(), info -> new BoundedBody()); }
        catch (Exception failure) { throw new IllegalStateException("Remote request failed; credentials and URL suppressed"); }
        {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (allow404 && response.statusCode() == 404) return new Response(404, HistoryPlan.JSON.createObjectNode());
                throw new IllegalStateException("Remote HTTP status " + response.statusCode());
            }
            byte[] bytes = response.body();
            if (bytes.length > 16 * 1024 * 1024) throw new IllegalStateException("Remote response too large");
            return new Response(response.statusCode(), HistoryPlan.JSON.readTree(bytes));
        }
    }

    /** Completes inside HttpClient's request deadline, including slow response bodies. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > 16 * 1024 * 1024 - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("Remote response too large")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
