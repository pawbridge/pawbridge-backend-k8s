package com.pawbridge.apigateway.filter;

import com.pawbridge.apigateway.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.filter.factory.RewritePathGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.assertThat;

class AdminStatisticsRoutingTest {
    private static final String SECRET = "test-only-admin-statistics-secret-at-least-64-bytes-long-for-hs512-signature";
    private final JwtAuthorizationGatewayFilterFactory jwt = new JwtAuthorizationGatewayFilterFactory(new JwtUtil(SECRET));

    @ParameterizedTest
    @ValueSource(strings = {"/api/admin/stats/intake-trend", "/api/admin/users/shelter-applications/stats",
            "/api/admin/posts/stats/period", "/api/admin/stats/daily-animals",
            "/api/admin/users/stats/daily-signups", "/api/admin/posts/stats/today"})
    void givenStatisticsRoute__whenMatchedRewrittenAndAuthorized__thenOnlyAdminIsForwarded(String path) {
        Map<String, Object> route = matchingRoute(path);
        assertThat(route.get("id")).isEqualTo(path.contains("/posts/") ? "admin-posts"
                : path.contains("/users/") ? "admin-users" : "admin-animal-stats");
        var filters = (List<String>) route.get("filters");
        assertThat(filters).hasSize(2);
        assertThat(filters.get(1)).isEqualTo("JwtAuthorization");
        var definition = new FilterDefinition(filters.get(0));
        assertThat(definition.getName()).isEqualTo("RewritePath");
        var rewrite = new RewritePathGatewayFilterFactory().apply(new RewritePathGatewayFilterFactory.Config()
                .setRegexp(definition.getArgs().get("_genkey_0")).setReplacement(definition.getArgs().get("_genkey_1")));

        for (String role : List.of("missing", "invalid", "ROLE_USER", "ROLE_SHELTER", "ROLE_ADMIN")) {
            var request = MockServerHttpRequest.get(path + "?startDate=2026-10-01&endDate=2026-10-02");
            if (!role.equals("missing")) request.header("Authorization", "Bearer " + (role.equals("invalid") ? "invalid-test-token" : token(role)));
            var exchange = MockServerWebExchange.from(request);
            var forwarded = new AtomicReference<ServerWebExchange>();
            rewrite.filter(exchange, rewritten -> jwt.apply(new JwtAuthorizationGatewayFilterFactory.Config())
                    .filter(rewritten, authorized -> { forwarded.set(authorized); return Mono.empty(); })).block();
            if (role.equals("ROLE_ADMIN")) {
                assertThat(forwarded.get().getRequest().getURI().getPath()).isEqualTo(path.replace("/api/admin/", "/api/v1/admin/"));
                assertThat(forwarded.get().getRequest().getQueryParams().getFirst("startDate")).isEqualTo("2026-10-01");
                assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-User-Role")).isEqualTo("ROLE_ADMIN");
            } else {
                assertThat(forwarded.get()).isNull();
                assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(role.startsWith("ROLE_") ? 403 : 401);
            }
        }
    }

    private String token(String role) {
        return Jwts.builder().subject("admin@example.invalid").claim("userId", 1L)
                .claim("name", "테스트 관리자").claim("role", role).expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private Map<String, Object> matchingRoute(String path) {
        try (var stream = getClass().getResourceAsStream("/application.yml")) {
            Map<String, Object> yaml = new Yaml().load(stream);
            Map<String, Object> spring = (Map<String, Object>) yaml.get("spring");
            Map<String, Object> cloud = (Map<String, Object>) spring.get("cloud");
            Map<String, Object> gateway = (Map<String, Object>) cloud.get("gateway");
            List<Map<String, Object>> routes = (List<Map<String, Object>>) gateway.get("routes");
            return routes.stream().filter(route -> {
                List<String> predicates = (List<String>) route.get("predicates");
                return predicates.stream().filter(value -> value.startsWith("Path="))
                        .anyMatch(value -> new PathRoutePredicateFactory().apply(new PathRoutePredicateFactory.Config()
                                .setPatterns(Arrays.stream(value.substring(5).split(",")).map(String::strip).toList()))
                                .test(MockServerWebExchange.from(MockServerHttpRequest.get(path))));
            }).findFirst().orElseThrow();
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }
}
