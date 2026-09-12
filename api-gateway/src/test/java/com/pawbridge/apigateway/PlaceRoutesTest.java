package com.pawbridge.apigateway;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises production YAML predicates, rewrites, security and CORS against a loopback upstream. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=test",
        "server.address=127.0.0.1",
        "jwt.secret=synthetic-local-test-key-not-a-production-secret-12345678901234567890",
        "management.tracing.enabled=false"
})
class PlaceRoutesTest {
    private static final AtomicInteger UPSTREAM_CALLS = new AtomicInteger();
    private static final DisposableServer UPSTREAM = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> {
                UPSTREAM_CALLS.incrementAndGet();
                int status = request.path().endsWith("/404") ? 404
                        : request.path().endsWith("/503") ? 503 : 200;
                return response.status(status).header("Content-Type", "text/plain;charset=UTF-8")
                        .sendString(Mono.just(request.uri()));
            }).bindNow(Duration.ofSeconds(10));

    @LocalServerPort
    private int port;
    private WebTestClient client;

    @DynamicPropertySource
    static void localUpstream(DynamicPropertyRegistry registry) {
        // Never resolve a service container name or send test traffic to a real service.
        for (var service : new String[]{"ANIMAL", "USER", "STORE", "COMMUNITY", "PAYMENT"}) {
            registry.add(service + "_SERVICE_URL", () -> "http://127.0.0.1:" + UPSTREAM.port());
        }
    }

    @BeforeEach
    void setUp() {
        UPSTREAM_CALLS.set(0);
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void closeUpstream() { UPSTREAM.disposeNow(); }

    @ParameterizedTest
    @CsvSource({
            "/api/places/regions, /api/v1/places/regions",
            "/api/places?areaCode=1&extra=a%2Bb&extra=c, /api/v1/places?areaCode=1&extra=a%2Bb&extra=c",
            "/api/places/126747, /api/v1/places/126747",
            "/api/v1/places/regions, /api/v1/places/regions",
            "/api/v1/places?areaCode=1, /api/v1/places?areaCode=1",
            "/api/v1/places/126747, /api/v1/places/126747"
    })
    void givenAnonymousGet__whenBrowsePlaces__thenForwardPathAndRawQueryOnce(String path, String expected) {
        client.get().uri(URI.create("http://127.0.0.1:" + port + path)).exchange()
                .expectStatus().isOk().expectBody(String.class).isEqualTo(expected);
        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
    }

    @Test
    void givenStaleBrowserToken__whenPublicPlaceRead__thenNoLoginRequired() {
        client.get().uri("/api/places/regions").header("Authorization", "Bearer synthetic-invalid-token")
                .exchange().expectStatus().isOk();
        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
    }

    @Test
    void givenFrontendPreflight__whenRequestPlaceGet__thenAllowOriginWithoutCallingUpstream() {
        client.options().uri("/api/places?areaCode=1")
                .header("Origin", "https://www.pawbridge.kr")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://www.pawbridge.kr")
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true");
        assertThat(UPSTREAM_CALLS.get()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/places", "PUT, /api/places/126747",
            "PATCH, /api/places/126747", "DELETE, /api/places/126747",
            "POST, /api/v1/places", "PUT, /api/v1/places/126747",
            "PATCH, /api/v1/places/126747", "DELETE, /api/v1/places/126747"
    })
    void givenPlaceWrite__whenRequestGateway__thenNoRouteOrUpstreamCall(String method, String path) {
        client.method(HttpMethod.valueOf(method)).uri(path).exchange().expectStatus().isNotFound();
        assertThat(UPSTREAM_CALLS.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/places-admin", "/api/places/126747/internal", "/api/v1/places/126747/internal"})
    void givenOutsidePlaceReadContract__whenRequestGateway__thenNoRouteOrUpstreamCall(String path) {
        client.get().uri(path).exchange().expectStatus().isNotFound();
        assertThat(UPSTREAM_CALLS.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 503})
    void givenUpstreamError__whenReadPlace__thenPreserveStatusWithoutRetry(int status) {
        client.get().uri("/api/places/" + status).exchange().expectStatus().isEqualTo(status)
                .expectBody(String.class).isEqualTo("/api/v1/places/" + status);
        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({
            "/api/animals?region=1, /api/v1/animals?region=1",
            "/api/shelters/12, /api/v1/shelters/12",
            "/api/v1/animals/12, /api/v1/animals/12",
            "/api/v1/shelters?keyword=test, /api/v1/shelters?keyword=test"
    })
    void givenExistingAnonymousRead__whenRequestGateway__thenPreserveAnimalAndShelterRoutes(String path, String expected) {
        client.get().uri(path).exchange().expectStatus().isOk().expectBody(String.class).isEqualTo(expected);
        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/animals", "/api/shelters", "/api/v1/animals", "/api/v1/shelters"})
    void givenExistingWriteWithoutToken__whenRequestGateway__thenStillRequireAuthentication(String path) {
        client.post().uri(path).exchange().expectStatus().isUnauthorized();
        assertThat(UPSTREAM_CALLS.get()).isZero();
    }
}
