package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pawbridge.backend.boot.security.GatewayCors;
import com.pawbridge.backend.boot.security.LegacyApiPathFilter;
import com.pawbridge.backend.boot.security.LostSearchLimitsFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Pure servlet-level tests of the filters that replace the API Gateway; no Spring context or database. */
class GatewayFeaturesUnitTest {
    private static final String DEFAULT_ORIGINS = GatewayCors.DEFAULT_ORIGIN_PATTERNS;

    // ---- CORS ----

    private static MockHttpServletResponse cors(String origins, MockHttpServletRequest request, MockFilterChain chain) throws Exception {
        var response = new MockHttpServletResponse();
        GatewayCors.filter(origins).doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest preflight(String origin, String method, String headers) {
        var request = new MockHttpServletRequest("OPTIONS", "/api/v1/posts");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", method);
        if (headers != null) request.addHeader("Access-Control-Request-Headers", headers);
        return request;
    }

    @ParameterizedTest
    @CsvSource({"http://localhost:3000", "http://127.0.0.1:3000", "http://localhost:5173", "http://127.0.0.1:5173",
            "https://pawbridge.kr", "https://www.pawbridge.kr", "https://admin.pawbridge.kr"})
    void given__original_gateway_origin__when__preflight__then__answer_without_reaching_the_chain(String origin) throws Exception {
        var chain = new MockFilterChain();
        var response = cors(DEFAULT_ORIGINS, preflight(origin, "PATCH", "authorization, content-type"), chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
        assertThat(response.getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("authorization");
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"https://evil.example,POST,authorization", "https://pawbridge.kr.evil.example,POST,authorization",
            "http://pawbridge.kr,POST,authorization", "https://pawbridge.kr,HEAD,authorization", "https://pawbridge.kr,POST,x-forbidden"})
    void given__foreign_origin_method_or_header__when__preflight__then__reject(String origin, String method, String headers) throws Exception {
        var response = cors(DEFAULT_ORIGINS, preflight(origin, method, headers), new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void given__allowed_origin__when__actual_request__then__expose_retry_after_and_authorization() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/animals/lost-candidates");
        request.addHeader("Origin", "https://www.pawbridge.kr");
        var chain = new MockFilterChain();
        var response = cors(DEFAULT_ORIGINS, request, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://www.pawbridge.kr");
        assertThat(response.getHeader("Access-Control-Expose-Headers")).contains("Retry-After", "Authorization");
    }

    @Test
    void given__foreign_origin__when__actual_request__then__continue_without_cors_headers() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/visits/today");
        request.addHeader("Origin", "https://foreign.invalid");
        var chain = new MockFilterChain();
        var response = cors(DEFAULT_ORIGINS, request, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void given__origin_override__when__preflight__then__only_the_override_is_allowed() throws Exception {
        assertThat(cors("https://staging.example", preflight("https://staging.example", "GET", null), new MockFilterChain()).getStatus()).isEqualTo(200);
        assertThat(cors("https://staging.example", preflight("https://pawbridge.kr", "GET", null), new MockFilterChain()).getStatus()).isEqualTo(403);
    }

    // ---- legacy path rewrite ----

    private record Seen(String uri, String servletPath, String pathInfo, String query, String original, int status) {}

    private static Seen legacy(String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setQueryString("a=1");
        request.setServletPath(path);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new LegacyApiPathFilter().doFilter(request, response, chain);
        var seen = (HttpServletRequest) chain.getRequest();
        if (seen == null) return new Seen(null, null, null, null, null, response.getStatus());
        return new Seen(seen.getRequestURI(), seen.getServletPath(), seen.getPathInfo(), seen.getQueryString(),
                (String) seen.getAttribute(LegacyApiPathFilter.ORIGINAL_PATH_ATTRIBUTE), response.getStatus());
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/api/users/me,/api/v1/users/me", "PUT,/api/users/me/nickname,/api/v1/users/me/nickname",
            "POST,/api/auth/login,/api/v1/auth/login", "GET,/api/favorites/5/check,/api/v1/favorites/5/check",
            "GET,/api/animals,/api/v1/animals", "GET,/api/animals/7,/api/v1/animals/7", "POST,/api/animals,/api/v1/animals",
            "DELETE,/api/animals/7,/api/v1/animals/7", "PATCH,/api/shelters/3,/api/v1/shelters/3", "GET,/api/shelters/count,/api/v1/shelters/count",
            "POST,/api/animals/9/chat/messages,/api/v1/animals/9/chat/messages",
            "GET,/api/places,/api/v1/places", "GET,/api/places/regions,/api/v1/places/regions", "GET,/api/places/123,/api/v1/places/123",
            "GET,/api/chats/socket,/api/v1/chats/socket", "GET,/api/chats/abc/messages,/api/v1/chats/abc/messages", "POST,/api/chats/connection-ticket,/api/v1/chats/connection-ticket",
            "PUT,/api/notes/3/read,/api/v1/notes/3/read", "GET,/api/notes/stream,/api/v1/notes/stream", "GET,/api/notes,/api/v1/notes",
            "POST,/api/visits/identity,/api/v1/visits/identity", "GET,/api/visits/today,/api/v1/visits/today", "POST,/api/visits/today,/api/v1/visits/today",
            "GET,/api/admin/visits/stats/period,/api/v1/admin/visits/stats/period", "GET,/api/home/videos,/api/v1/home/videos",
            "GET,/api/admin/videos,/api/v1/admin/videos", "PUT,/api/admin/videos/3/publication,/api/v1/admin/videos/3/publication",
            "GET,/api/posts/read/3,/api/v1/posts/read/3", "POST,/api/posts,/api/v1/posts", "GET,/api/comments/posts/read/3,/api/v1/comments/posts/read/3",
            "DELETE,/api/reports/4,/api/v1/reports/4", "GET,/api/reports,/api/v1/reports",
            "GET,/api/products/5,/api/v1/products/5", "POST,/api/products,/api/v1/products", "DELETE,/api/categories/1,/api/v1/categories/1",
            "POST,/api/images,/api/v1/images", "GET,/api/option-groups/3,/api/v1/option-groups/3", "POST,/api/option-groups/3/values,/api/v1/option-groups/3/values",
            "PUT,/api/option-groups/values/9,/api/v1/option-groups/values/9",
            "GET,/api/admin/orders,/api/v1/admin/orders", "PATCH,/api/admin/orders/1/status,/api/v1/admin/orders/1/status",
            "GET,/api/carts,/api/v1/carts", "DELETE,/api/carts,/api/v1/carts", "POST,/api/carts/items,/api/v1/carts/items", "PATCH,/api/carts/items/9,/api/v1/carts/items/9",
            "DELETE,/api/carts/items/9,/api/v1/carts/items/9", "GET,/api/orders,/api/v1/orders", "POST,/api/orders/direct,/api/v1/orders/direct",
            "GET,/api/admin/users/stats/total-users,/api/v1/admin/users/stats/total-users", "GET,/api/admin/stats/daily-signups,/api/v1/admin/stats/daily-signups",
            "GET,/api/admin/posts/stats/today,/api/v1/admin/posts/stats/today", "GET,/api/admin/stats/intake-trend,/api/v1/admin/stats/intake-trend",
            "GET,/api/admin/stats/daily-animals,/api/v1/admin/stats/daily-animals"})
    void given__old_route_the_gateway_rewrote__when__filtering__then__present_the_v1_path(String method, String path, String expected) throws Exception {
        var seen = legacy(method, path);
        assertThat(seen.uri()).isEqualTo(expected);
        assertThat(seen.servletPath()).isEqualTo(expected);
        assertThat(seen.pathInfo()).isNull();
        assertThat(seen.query()).isEqualTo("a=1");
        assertThat(seen.original()).isEqualTo(path);
    }

    @ParameterizedTest
    @CsvSource({
            // never routed by the Gateway, or routed only for other methods
            "POST,/api/carts/reset", "DELETE,/api/carts/reset", "GET,/api/carts/items/9", "POST,/api/carts", "PUT,/api/carts",
            "POST,/api/places", "GET,/api/places/1/extra", "GET,/api/visits/identity", "PUT,/api/visits/today", "POST,/api/admin/visits/stats/period",
            "POST,/api/home/videos", "GET,/api/images", "GET,/api/option-groups/3/values", "POST,/api/admin/orders", "GET,/api/admin/stats/other",
            "HEAD,/api/animals", "TRACE,/api/products/1", "GET,/api/unknown", "GET,/api/batch/apms/sync", "POST,/api/batch/apms/sync",
            // the public upload must not become reachable through the old protected alias
            "POST,/api/animals/lost-candidates",
            // already served unchanged, or outside /api
            "POST,/api/payments/confirm", "GET,/api/v1/animals", "POST,/api/v1/animals/lost-candidates", "GET,/internal/animals/lost-gallery",
            "GET,/oauth2/authorization/google", "GET,/login/oauth2/code/google", "GET,/actuator/health", "GET,/api/v1", "GET,/apix/animals"})
    void given__path_or_method_the_gateway_did_not_rewrite__when__filtering__then__leave_it_alone(String method, String path) throws Exception {
        var seen = legacy(method, path);
        assertThat(seen.uri()).isEqualTo(path);
        assertThat(seen.original()).isNull();
        assertThat(seen.status()).isEqualTo(200);
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/users/internal/5/nickname", "PUT,/api/v1/users/internal/5/state", "DELETE,/api/users/internal/x"})
    void given__service_to_service_user_path__when__filtering__then__refuse_with_the_gateway_404(String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new LegacyApiPathFilter().doFilter(request, response, chain);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":404,\"message\":\"유효하지 않은 엔드포인트입니다.\",\"data\":null}");
    }

    // ---- lost-candidates limits ----

    private static final String TYPE = "multipart/form-data; boundary=x";

    private static MockHttpServletRequest search(String contentType, byte[] body) {
        var request = new MockHttpServletRequest("POST", LostSearchLimitsFilter.PATH);
        if (contentType != null) request.setContentType(contentType);
        if (body != null) request.setContent(body);
        request.addHeader("Authorization", "Bearer secret");
        request.addHeader("X-User-Id", "99");
        request.addHeader("X-Internal-Api-Key", "forged");
        request.addHeader("Accept", "application/json");
        return request;
    }

    private static MockHttpServletResponse run(LostSearchLimitsFilter filter, MockHttpServletRequest request, FilterChain chain) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static void assertRejected(MockHttpServletResponse response, int status, String message, String retryAfter) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("{\"message\":\"" + message + "\"}");
        assertThat(response.getHeader("Retry-After")).isEqualTo(retryAfter);
    }

    @Test
    void given__oversized_body__when__searching__then__413() throws Exception {
        var filter = new LostSearchLimitsFilter(10, 2, 30, 50);
        var chain = new MockFilterChain();
        assertRejected(run(filter, search(TYPE, new byte[11]), chain), 413, "사진 크기 제한을 초과했습니다", null);
        assertThat(chain.getRequest()).isNull();
        // exactly at the limit is admitted
        assertThat(run(filter, search(TYPE, new byte[10]), chain).getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void given__non_multipart_or_malformed_type__when__searching__then__415() throws Exception {
        var filter = new LostSearchLimitsFilter(100, 2, 30, 50);
        for (String type : new String[]{null, "application/json", "text/plain", "multipart", "not a media type"}) {
            assertRejected(run(filter, search(type, new byte[]{1}), new MockFilterChain()), 415, "사진 업로드 형식을 확인해 주세요", null);
        }
    }

    @Test
    void given__declared_length_missing__when__searching__then__refuse_before_counting() throws Exception {
        var filter = new LostSearchLimitsFilter(100, 1, 1, 50);
        assertRejected(run(filter, search(TYPE, null), new MockFilterChain()), 411, "사진 업로드 형식을 확인해 주세요", null);
        // the refusal consumed neither the concurrency permit nor the per-minute budget
        assertThat(run(filter, search(TYPE, new byte[]{1}), new MockFilterChain()).getStatus()).isEqualTo(200);
    }

    @Test
    void given__empty_body__when__searching__then__400() throws Exception {
        var filter = new LostSearchLimitsFilter(100, 2, 30, 50);
        assertRejected(run(filter, search(TYPE, new byte[0]), new MockFilterChain()), 400, "사진을 선택해 주세요", null);
    }

    @Test
    void given__two_requests_in_flight__when__a_third_arrives__then__503_with_retry_after_3() throws Exception {
        var filter = new LostSearchLimitsFilter(100, 2, 30, 50);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        FilterChain slow = (req, res) -> { entered.countDown(); try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
        var workers = new Thread[2];
        for (int i = 0; i < 2; i++) {
            workers[i] = new Thread(() -> { try { run(filter, search(TYPE, new byte[]{1}), slow); } catch (Exception ignored) { } });
            workers[i].start();
        }
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertRejected(run(filter, search(TYPE, new byte[]{1}), new MockFilterChain()), 503, "잠시 후 다시 시도해 주세요", "3");
        release.countDown();
        for (var worker : workers) worker.join(10_000);
        // permits return after completion
        assertThat(run(filter, search(TYPE, new byte[]{1}), new MockFilterChain()).getStatus()).isEqualTo(200);
    }

    @Test
    void given__per_minute_budget_spent__when__searching__then__429_with_retry_after_60_and_permit_returned() throws Exception {
        var filter = new LostSearchLimitsFilter(100, 1, 2, 50);
        assertThat(run(filter, search(TYPE, new byte[]{1}), new MockFilterChain()).getStatus()).isEqualTo(200);
        assertThat(run(filter, search(TYPE, new byte[]{1}), new MockFilterChain()).getStatus()).isEqualTo(200);
        assertRejected(run(filter, search(TYPE, new byte[]{1}), new MockFilterChain()), 429,
                "검색 요청이 많습니다. 잠시 후 다시 시도해 주세요", "60");
        // the 429 released its concurrency permit instead of leaking it (a leak would turn this into 503)
        assertRejected(run(filter, search(TYPE, new byte[]{1}), new MockFilterChain()), 429,
                "검색 요청이 많습니다. 잠시 후 다시 시도해 주세요", "60");
    }

    @Test
    void given__a_timeout_escaping_the_handler__when__searching__then__504() throws Exception {
        var filter = new LostSearchLimitsFilter(100, 1, 30, 50);
        FilterChain timesOut = (req, res) -> { throw new ServletException(new TimeoutException()); };
        assertRejected(run(filter, search(TYPE, new byte[]{1}), timesOut), 504, "검색 시간이 초과됐습니다", null);
        FilterChain broken = (req, res) -> { throw new ServletException(new IllegalStateException("boom")); };
        assertThatThrownBy(() -> run(filter, search(TYPE, new byte[]{1}), broken)).isInstanceOf(ServletException.class);
        assertThat(run(filter, search(TYPE, new byte[]{1}), new MockFilterChain()).getStatus()).isEqualTo(200);
    }

    @Test
    void given__admitted_request__when__forwarding__then__client_identity_and_internal_key_headers_are_hidden() throws Exception {
        var filter = new LostSearchLimitsFilter(100, 2, 30, 50);
        var seen = new AtomicReference<HttpServletRequest>();
        var response = run(filter, search(TYPE, new byte[]{1}), (req, res) -> seen.set((HttpServletRequest) req));
        assertThat(response.getStatus()).isEqualTo(200);
        var forwarded = seen.get();
        for (String name : new String[]{"Authorization", "authorization", "X-User-Id", "X-Internal-Api-Key", "Transfer-Encoding"}) {
            assertThat(forwarded.getHeader(name)).isNull();
            assertThat(Collections.list(forwarded.getHeaders(name))).isEmpty();
        }
        assertThat(Collections.list(forwarded.getHeaderNames())).extracting(String::toLowerCase)
                .contains("accept", "content-type").doesNotContain("authorization", "x-user-id", "x-internal-api-key");
    }

    @Test
    void given__other_routes__when__filtering__then__apply_no_limits() throws Exception {
        var filter = new LostSearchLimitsFilter(1, 1, 1, 50);
        for (String[] route : new String[][]{{"GET", LostSearchLimitsFilter.PATH}, {"POST", "/api/v1/animals"},
                {"POST", "/api/v1/animals/lost-candidates/extra"}}) {
            var chain = new MockFilterChain();
            var request = new MockHttpServletRequest(route[0], route[1]);
            assertThat(run(filter, request, chain).getStatus()).isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
        }
    }

    @Test
    void given__non_positive_limit__when__creating__then__refuse() {
        assertThatThrownBy(() -> new LostSearchLimitsFilter(0, 2, 30, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LostSearchLimitsFilter(1, 0, 30, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LostSearchLimitsFilter(1, 2, 0, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LostSearchLimitsFilter(1, 2, 30, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
