package com.pawbridge.animalservice.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class TourApiClient {
    static final int MAX_BYTES = 1024 * 1024;
    static final String BASE = "https://apis.data.go.kr/B551011/KorPetTourService2/";
    private static final Set<String> FIELDS = Set.of("code", "name", "contentid", "title", "addr1", "overview", "firstimage", "cpyrhtDivCd",
            "acmpyTypeCd", "acmpyPsblCpam", "acmpyNeedMtr", "etcAcmpyInfo",
            "relaAcdntRiskMtr", "relaPosesFclty", "relaFrnshPrdlst", "areacode", "sigungucode",
            "modifiedtime", "showflag", "addr2", "contenttypeid", "mapx", "mapy", "lDongRegnCd", "lDongSignguCd");
    public enum Operation {
        REGIONS("ldongCode2", 50), PLACES("areaBasedList2", 10),
        COMMON("detailCommon2", 1), PET("detailPetTour2", 1), SYNC("petTourSyncList2", 100);
        final String path;
        final int rows;
        Operation(String path, int rows) { this.path = path; this.rows = rows; }
    }

    private final TourApiProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    @Autowired
    public TourApiClient(TourApiProperties properties, ObjectMapper mapper) {
        this(properties, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    TourApiClient(TourApiProperties properties, ObjectMapper mapper, HttpClient http) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = http;
    }

    public List<Map<String, String>> fetch(Operation operation, String argument) {
        return fetchPage(operation, argument, 1).items();
    }

    public record Page(List<Map<String, String>> items, int totalCount) {}

    public Page fetchPage(Operation operation, String argument, int pageNo) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            if (!properties.isEnabled()) throw PetTravelException.unavailable();
            var key = decodedKey();
            var query = new LinkedHashMap<String, String>();
            query.put("serviceKey", key);
            query.put("MobileOS", "ETC");
            query.put("MobileApp", "PawBridge");
            query.put("_type", "json");
            if (pageNo < 1 || pageNo > 100000) throw PetTravelException.unavailable();
            query.put("pageNo", Integer.toString(pageNo));
            query.put("numOfRows", Integer.toString(operation.rows));
            if (operation == Operation.SYNC) {
                if (!"0".equals(argument) && !"1".equals(argument)) throw PetTravelException.unavailable();
                query.put("showflag", argument);
                query.put("arrange", "A");
            } else if (operation == Operation.PLACES) {
                if (!argument.matches("[0-9]{1,2}")) throw PetTravelException.unavailable();
                query.put("areaCode", argument);
                query.put("arrange", "A");
            } else if (operation != Operation.REGIONS) {
                if (!argument.matches("[0-9]{1,20}")) throw PetTravelException.unavailable();
                query.put("contentId", argument);
            }
            var encoded = query.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                    .collect(java.util.stream.Collectors.joining("&"));
            var request = HttpRequest.newBuilder(URI.create(BASE + operation.path + "?" + encoded))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json").GET().build();
            pending = http.sendAsync(request, info -> new LimitedBody());
            // Includes body consumption; a slow response body cannot hold a caller indefinitely.
            var response = pending.get(6, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw PetTravelException.unavailable();
            var items = parse(response.body(), operation, key);
            int total = items.size();
            if (operation == Operation.SYNC) {
                var count = mapper.readTree(response.body()).path("response").path("body").path("totalCount");
                if (!count.asText().matches("[0-9]{1,8}")) throw PetTravelException.unavailable();
                total = Integer.parseInt(count.asText());
                if (total < items.size()) throw PetTravelException.unavailable();
            }
            return new Page(items, total);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw PetTravelException.unavailable();
        } catch (Exception exception) {
            // Never retain a cause: HTTP exceptions may include the credential-bearing URI.
            throw PetTravelException.unavailable();
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private String decodedKey() {
        try {
            var raw = properties.getServiceKey();
            var key = URLDecoder.decode(raw.trim().replace("+", "%2B"), StandardCharsets.UTF_8);
            if (!key.matches("[A-Za-z0-9+/=_-]{16,2048}")) throw PetTravelException.unavailable();
            return key;
        } catch (RuntimeException exception) {
            throw PetTravelException.unavailable();
        }
    }

    List<Map<String, String>> parse(byte[] bytes, Operation operation, String key) {
        try {
            if (bytes.length > MAX_BYTES) throw PetTravelException.unavailable();
            var response = mapper.readTree(bytes).path("response");
            if (!"0000".equals(response.path("header").path("resultCode").asText())) throw PetTravelException.unavailable();
            var body = response.path("body");
            if (!body.isObject()) throw PetTravelException.unavailable();
            var items = body.path("items");
            if (items.isMissingNode() || items.isNull() || (items.isTextual() && items.asText().isEmpty())
                    || (items.isObject() && items.isEmpty())) return List.of();
            if (!items.isObject()) throw PetTravelException.unavailable();
            var records = items.path("item");
            List<JsonNode> rows = new ArrayList<>();
            if (records.isObject()) rows.add(records);
            else if (records.isArray()) records.forEach(rows::add);
            else throw PetTravelException.unavailable();
            if (rows.size() > operation.rows) throw PetTravelException.unavailable();
            var result = new ArrayList<Map<String, String>>();
            for (var row : rows) {
                if (!row.isObject()) throw PetTravelException.unavailable();
                var values = new LinkedHashMap<String, String>();
                for (var field : FIELDS) {
                    var value = row.path(field);
                    if (value.isTextual() || value.isNumber()) values.put(field, clean(value.asText(), key));
                }
                if (operation == Operation.REGIONS) {
                    if (!values.getOrDefault("code", "").matches("[0-9]{2,5}") || values.getOrDefault("name", "").isBlank())
                        throw PetTravelException.unavailable();
                } else if (!values.getOrDefault("contentid", "").matches("[0-9]{1,20}")
                        || (operation != Operation.PET && operation != Operation.SYNC && values.getOrDefault("title", "").isBlank())) {
                    throw PetTravelException.unavailable();
                }
                result.add(Map.copyOf(values));
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            throw PetTravelException.unavailable();
        }
    }

    private String clean(String value, String key) {
        var text = HtmlUtils.htmlUnescape(value);
        for (var secret : List.of(key, encode(key))) {
            text = Pattern.compile(Pattern.quote(secret), Pattern.CASE_INSENSITIVE).matcher(text).replaceAll("[REDACTED]");
        }
        // This is plain text, never trusted HTML. Callers must render as text too.
        text = text.replaceAll("(?is)</?[a-z][a-z0-9]*(?:\\s[^<>]*)?/?>|<!--.*?-->", " ")
                .replaceAll("[\\p{Cntrl}]", " ").strip();
        // Reject unusually large fields instead of silently dropping a restriction at the end.
        if (text.length() > 10000) throw PetTravelException.unavailable();
        return text;
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int received;
        private boolean failed;
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            long incoming = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (received + incoming > MAX_BYTES) {
                failed = true;
                subscription.cancel();
                delegate.onError(PetTravelException.unavailable());
                return;
            }
            received += (int) incoming;
            delegate.onNext(buffers);
        }
        @Override public void onError(Throwable throwable) { delegate.onError(PetTravelException.unavailable()); }
        @Override public void onComplete() { if (!failed) delegate.onComplete(); }
    }
}
