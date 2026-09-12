package com.pawbridge.animalservice.photo;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

public class PhotoArchiveHttpClient {
    private final HttpClient http;
    private final URI optimizer;
    private final String key;
    private final Set<String> allowedHosts;

    public PhotoArchiveHttpClient(PhotoArchiveProperties properties) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), properties);
    }
    PhotoArchiveHttpClient(HttpClient http, PhotoArchiveProperties properties) {
        this.http = http;
        this.optimizer = properties.getOptimizerUrl();
        this.key = properties.getInternalApiKey();
        this.allowedHosts = Set.copyOf(properties.getAllowedHosts());
    }

    public byte[] download(String url) {
        URI uri;
        try { uri = URI.create(url); }
        catch (RuntimeException malformed) { throw new PhotoArchiveFailure("SOURCE_URL", true); }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !Set.of("http", "https").contains(uri.getScheme())
                || !allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))
                || (uri.getPort() != -1 && uri.getPort() != ("https".equals(uri.getScheme()) ? 443 : 80))) {
            throw new PhotoArchiveFailure("SOURCE_URL", true);
        }
        var response = send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(), 30);
        checkStatus(response.statusCode(), "SOURCE");
        if (response.body().length == 0) throw new PhotoArchiveFailure("SOURCE_EMPTY", true);
        return response.body();
    }

    public ArchivedPhoto optimize(byte[] source) {
        if (source.length == 0 || source.length > ArchivedPhoto.MAX_BYTES)
            throw new PhotoArchiveFailure("SOURCE_SIZE", true);
        var request = HttpRequest.newBuilder(optimizer).timeout(Duration.ofSeconds(60))
                .header("X-Internal-API-Key", key).header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(source)).build();
        var response = send(request, 60);
        checkStatus(response.statusCode(), "OPTIMIZER");
        try {
            byte[] stored = response.body();
            String mime = header(response, "Content-Type");
            String sourceHash = header(response, "X-Source-Sha256");
            String storedHash = header(response, "X-Stored-Sha256");
            String recipe = header(response, "X-Photo-Recipe");
            int width = Integer.parseInt(header(response, "X-Photo-Width"));
            int height = Integer.parseInt(header(response, "X-Photo-Height"));
            boolean original = "original-v1".equals(recipe);
            if (stored.length == 0 || stored.length > source.length
                    || !Set.of("image/jpeg", "image/png", "image/webp").contains(mime)
                    || !sourceHash.equals(ArchivedPhoto.sha256(source))
                    || !storedHash.equals(ArchivedPhoto.sha256(stored))
                    || width <= 0 || height <= 0 || (long) width * height > 16_000_000
                    || (original && !Arrays.equals(source, stored))
                    || (!original && !("webp-q90-m4-fullsize-v1".equals(recipe)
                        && "image/webp".equals(mime) && stored.length < source.length))) {
                throw new IllegalArgumentException();
            }
            return new ArchivedPhoto(stored, sourceHash, storedHash, mime, width, height, recipe);
        } catch (RuntimeException invalid) { throw new PhotoArchiveFailure("OPTIMIZER_CONTRACT", true); }
    }

    private static String header(HttpResponse<?> response, String name) {
        var values = response.headers().allValues(name);
        if (values.size() != 1) throw new IllegalArgumentException();
        return values.get(0);
    }

    private static void checkStatus(int status, String source) {
        if (status != 200) throw new PhotoArchiveFailure(source + "_HTTP_" + status,
                status >= 300 && status < 500 && status != 408 && status != 429);
    }

    private HttpResponse<byte[]> send(HttpRequest request, int seconds) {
        var future = http.sendAsync(request, info -> new LimitedBody());
        try {
            var response = future.get(seconds, TimeUnit.SECONDS);
            if (!response.headers().firstValue("Content-Encoding").orElse("identity").equalsIgnoreCase("identity"))
                throw new PhotoArchiveFailure("HTTP_ENCODING", true);
            return response;
        } catch (PhotoArchiveFailure known) { throw known; }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PhotoArchiveFailure("HTTP_INTERRUPTED", false);
        } catch (Exception failure) {
            if (failure.getCause() instanceof PhotoArchiveFailure known) throw known;
            throw new PhotoArchiveFailure("HTTP_TRANSFER", false);
        }
        finally { if (!future.isDone()) future.cancel(true); }
    }

    /** Cancels during streaming; a Content-Length header is not trusted as a size limit. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> items) {
            for (var item : items) {
                if ((long) output.size() + item.remaining() > ArchivedPhoto.MAX_BYTES) {
                    subscription.cancel(); result.completeExceptionally(new PhotoArchiveFailure("HTTP_SIZE", true)); return;
                }
                byte[] chunk = new byte[item.remaining()]; item.get(chunk); output.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(output.toByteArray()); }
    }
}
