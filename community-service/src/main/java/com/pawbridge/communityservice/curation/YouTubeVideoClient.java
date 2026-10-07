package com.pawbridge.communityservice.curation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.communityservice.curation.HomeVideoModels.Metadata;
import java.nio.ByteBuffer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("postgresql")
public class YouTubeVideoClient {
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final URI endpoint;

    @org.springframework.beans.factory.annotation.Autowired
    public YouTubeVideoClient(ObjectMapper mapper, @Value("${youtube.api-key:}") String apiKey) {
        this(mapper, apiKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("https://www.googleapis.com/youtube/v3/videos"));
    }

    // Package-visible only: isolated tests may supply a loopback provider fixture.
    YouTubeVideoClient(ObjectMapper mapper, String apiKey, HttpClient http, URI endpoint) {
        this.mapper = mapper; this.apiKey = apiKey; this.http = http; this.endpoint = endpoint;
    }

    public Map<String, Metadata> lookup(List<String> ids) {
        if (apiKey.isBlank()) throw unavailable();
        if (ids.isEmpty() || ids.size() > 50 || ids.stream().anyMatch(id -> !id.matches("[A-Za-z0-9_-]{11}"))) {
            throw new IllegalArgumentException("Invalid bounded video ID batch");
        }
        String query = "?part=snippet,contentDetails,status&id=" + String.join(",", ids)
                + "&fields=" + URLEncoder.encode("items(id,snippet(title,channelTitle,thumbnails,liveBroadcastContent),contentDetails(duration,regionRestriction,contentRating),status(privacyStatus,uploadStatus,embeddable))", StandardCharsets.UTF_8)
                + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var request = HttpRequest.newBuilder(URI.create(endpoint + query)).timeout(Duration.ofSeconds(8)).GET().build();
            pending = http.sendAsync(request, info -> new BoundedBody());
            var response = pending.get(10, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw unavailable();
            JsonNode root = mapper.readTree(response.body());
            if (!root.path("items").isArray()) throw unavailable();
            var result = new HashMap<String, Metadata>();
            for (JsonNode item : root.path("items")) {
                String id = item.path("id").asText();
                if (!ids.contains(id)) continue;
                JsonNode snippet = item.path("snippet"), details = item.path("contentDetails");
                JsonNode status = item.path("status");
                String title = snippet.path("title").asText(), channel = snippet.path("channelTitle").asText();
                String thumbnail = snippet.path("thumbnails").path("high").path("url").asText();
                if (thumbnail.isBlank()) thumbnail = snippet.path("thumbnails").path("medium").path("url").asText();
                if (thumbnail.isBlank()) thumbnail = snippet.path("thumbnails").path("default").path("url").asText();
                long duration = Duration.parse(details.path("duration").asText()).toSeconds();
                boolean playable = "public".equals(status.path("privacyStatus").asText())
                        && "processed".equals(status.path("uploadStatus").asText())
                        && status.path("embeddable").asBoolean(false)
                        && "none".equals(snippet.path("liveBroadcastContent").asText())
                        && details.path("contentRating").path("ytRating").isMissingNode()
                        && allowsKorea(details.path("regionRestriction"))
                        && duration > 0 && !title.isBlank() && title.length() <= 500
                        && !channel.isBlank() && channel.length() <= 300 && safeThumbnail(thumbnail);
                result.put(id, new Metadata(id, title, channel, thumbnail, duration, playable));
            }
            return Map.copyOf(result);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception failure) {
            // HTTP exceptions can contain the request URI and key. Never retain their cause or text.
            throw unavailable();
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    /** Bound both buffered bytes and the full-response deadline, including a stalled body. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long received;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) received += buffer.remaining();
            if (received > 524_288) {
                subscription.cancel(); delegate.onError(new IllegalStateException("Provider response limit exceeded"));
            } else delegate.onNext(buffers);
        }
        public void onError(Throwable failure) { delegate.onError(failure); }
        public void onComplete() { delegate.onComplete(); }
    }

    private static boolean safeThumbnail(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && "i.ytimg.com".equals(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPort() == -1 && value.length() <= 2048;
        } catch (IllegalArgumentException failure) { return false; }
    }
    private static boolean allowsKorea(JsonNode region) {
        if (region.path("blocked").isArray())
            for (JsonNode code : region.path("blocked")) if ("KR".equals(code.asText())) return false;
        if (region.path("allowed").isArray()) {
            for (JsonNode code : region.path("allowed")) if ("KR".equals(code.asText())) return true;
            return false;
        }
        return true;
    }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "YouTube 정보를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }
}
