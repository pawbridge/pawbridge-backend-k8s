package com.pawbridge.animalservice.shelter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded, read-only APMS collection. Never retain credential-bearing HTTP exceptions. */
@Component
public class ShelterDirectoryClient {
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
    private final String key;
    private final ObjectMapper mapper;
    public ShelterDirectoryClient(@Value("${shelter-directory.service-key:}") String key, ObjectMapper mapper) {
        this.key = key;
        this.mapper = mapper;
    }

    public List<Map<String, String>> collect() {
        if (key.isBlank()) throw unavailable();
        var collected = new LinkedHashMap<String, Map<String, String>>();
        // totalCount includes source duplicates. An explicit empty page terminates traversal.
        for (int page = 1; page <= 30; page++) {
            var rows = fetch(page);
            if (rows.isEmpty()) {
                if (collected.isEmpty()) throw unavailable();
                return List.copyOf(collected.values());
            }
            int before = collected.size();
            for (var row : rows) collected.merge(row.get("careRegNo"), row, (a, b) -> {
                if (!a.equals(b)) throw unavailable();
                return a;
            });
            if (before == collected.size()) throw unavailable();
        }
        throw unavailable();
    }

    List<Map<String, String>> fetch(int page) {
        java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<byte[]>> pending = null;
        try {
            String query = "?serviceKey=" + URLEncoder.encode(key.trim(), StandardCharsets.UTF_8)
                    + "&_type=json&numOfRows=1000&pageNo=" + page;
            var request = java.net.http.HttpRequest.newBuilder(URI.create(
                    "https://apis.data.go.kr/1543061/animalShelterSrvc_v2/shelterInfo_v2" + query))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            pending = http.sendAsync(request, info -> new LimitedBody());
            var response = pending.get(12, java.util.concurrent.TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw unavailable();
            return parse(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception exception) { throw unavailable(); }
        finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
    }

    List<Map<String, String>> parse(byte[] bytes) {
        try {
            JsonNode root = mapper.readTree(bytes);
            if (root.has("response")) root = root.get("response");
            if (!"00".equals(root.path("header").path("resultCode").asText())) throw unavailable();
            if (!root.path("body").isObject()) throw unavailable();
            var items = root.path("body").path("items");
            if (items.isTextual() && items.asText().isBlank()) return List.of();
            if (items.isObject() && items.isEmpty()) return List.of();
            if (!items.isObject() || !items.has("item")) throw unavailable();
            var node = items.get("item");
            if (!node.isArray() && !node.isObject()) throw unavailable();
            List<JsonNode> nodes = new ArrayList<>();
            if (node.isObject()) nodes.add(node); else node.forEach(nodes::add);
            if (nodes.size() > 1000) throw unavailable();
            var rows = new ArrayList<Map<String, String>>();
            for (var value : nodes) {
                var row = new LinkedHashMap<String, String>();
                for (var field : FIELDS) {
                    var v = value.path(field);
                    if (v.isValueNode() && !v.isNull() && !v.asText().isBlank()) {
                        if (v.asText().length() > 500) throw unavailable();
                        row.put(field, v.asText().trim());
                    }
                }
                if (!row.getOrDefault("careRegNo", "").matches("[0-9]{15}")
                        || !row.containsKey("careNm") || row.get("careNm").length() > 200) throw unavailable();
                rows.add(Map.copyOf(row));
            }
            return List.copyOf(rows);
        } catch (Exception exception) { throw unavailable(); }
    }

    private static final Set<String> FIELDS = Set.of("careRegNo", "careNm", "careAddr", "careTel", "orgNm",
            "lat", "lng", "weekOprStime", "weekOprEtime", "weekendOprStime", "weekendOprEtime", "closeDay", "dataStdDt");
    static IllegalStateException unavailable() { return new IllegalStateException("SHELTER_DIRECTORY_UNAVAILABLE"); }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int received;
        private boolean failed;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription; delegate.onSubscribe(subscription); }
        public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            long incoming=buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (received+incoming > 4*1024*1024) {
                failed=true; subscription.cancel(); delegate.onError(unavailable()); return;
            }
            received+=(int)incoming; delegate.onNext(buffers);
        }
        public void onError(Throwable throwable) { delegate.onError(unavailable()); }
        public void onComplete() { if (!failed) delegate.onComplete(); }
    }
}
