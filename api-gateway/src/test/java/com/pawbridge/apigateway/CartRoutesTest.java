package com.pawbridge.apigateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual Gateway routes/authentication; every downstream points to a loopback fixture. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=test", "server.address=127.0.0.1",
        "jwt.secret=synthetic-cart-test-key-not-a-production-secret-12345678901234567890",
        "management.tracing.enabled=false"
})
class CartRoutesTest {
    private static final String KEY = "synthetic-cart-test-key-not-a-production-secret-12345678901234567890";
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final DisposableServer STORE = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> {
                CALLS.incrementAndGet();
                int status = request.uri().contains("quantity=-1") ? 400 : 200;
                return response.status(status).header("Content-Type", "text/plain")
                        .sendString(request.receive().aggregate().asString().defaultIfEmpty("")
                                .map(body -> request.method().name() + " " + request.uri()
                                        + "|" + request.requestHeaders().get("X-User-Id", "")
                                        + "|" + body));
            }).bindNow(Duration.ofSeconds(10));

    @LocalServerPort private int port;
    private WebTestClient client;

    @DynamicPropertySource
    static void localServices(DynamicPropertyRegistry registry) {
        for (var service : new String[]{"ANIMAL", "USER", "STORE", "COMMUNITY", "PAYMENT"}) {
            registry.add(service + "_SERVICE_URL", () -> "http://127.0.0.1:" + STORE.port());
        }
    }

    @BeforeEach
    void setUp() {
        CALLS.set(0);
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void closeStore() { STORE.disposeNow(); }

    private static Stream<Arguments> operations() {
        return Stream.of(
                Arguments.of("GET", "/api/carts", "/api/v1/carts", ""),
                Arguments.of("POST", "/api/carts/items", "/api/v1/carts/items", "{\"skuId\":42,\"quantity\":2}"),
                Arguments.of("PATCH", "/api/carts/items/42?quantity=3&extra=a%2Bb", "/api/v1/carts/items/42?quantity=3&extra=a%2Bb", ""),
                Arguments.of("DELETE", "/api/carts/items/42", "/api/v1/carts/items/42", ""),
                Arguments.of("DELETE", "/api/carts", "/api/v1/carts", ""));
    }

    @ParameterizedTest
    @MethodSource("operations")
    void givenJwtAndForgedIdentity__whenCartOperation__thenForwardOnceWithVerifiedIdentityAndOriginalPayload(
            String method, String path, String expected, String body) {
        client.method(HttpMethod.valueOf(method)).uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token())
                .header("X-User-Id", "999", "1000").header("Content-Type", "application/json")
                .bodyValue(body).exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo(method + " " + expected + "|7|" + body);
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("operations")
    void givenAnonymous__whenCartOperation__then401WithoutStoreCall(String method, String path, String expected, String body) {
        client.method(HttpMethod.valueOf(method)).uri(path).header("X-User-Id", "7")
                .exchange().expectStatus().isUnauthorized();
        assertThat(CALLS.get()).isZero();
    }

    @ParameterizedTest
    @MethodSource("operations")
    void givenInvalidToken__whenCartOperation__then401WithoutStoreCall(String method, String path, String expected, String body) {
        client.method(HttpMethod.valueOf(method)).uri(path).header("Authorization", "Bearer invalid-cart-token")
                .exchange().expectStatus().isUnauthorized();
        assertThat(CALLS.get()).isZero();
    }

    @ParameterizedTest
    @MethodSource("operations")
    void givenFrontendPreflight__whenCartOperation__thenAllowWithoutTokenOrStoreCall(String method, String path, String expected, String body) {
        client.options().uri(path).header("Origin", "https://www.pawbridge.kr")
                .header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://www.pawbridge.kr")
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true");
        assertThat(CALLS.get()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"DELETE,/api/carts/reset", "DELETE,/api/carts/reset/", "DELETE,/api/v1/carts/reset",
            "GET,/api/carts-extra", "POST,/api/carts", "GET,/api/carts/items", "PUT,/api/carts/items/42",
            "DELETE,/api/carts/items/42/extra"})
    void givenOutsideCartContract__whenAuthenticatedRequest__thenNoRouteOrStoreCall(String method, String path) {
        client.method(HttpMethod.valueOf(method)).uri(path).header("Authorization", "Bearer " + token())
                .exchange().expectStatus().isNotFound();
        assertThat(CALLS.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/carts", "/api/carts/items"})
    void givenUntrustedOrigin__whenPreflight__thenRejectWithoutStoreCall(String path) {
        client.options().uri(path).header("Origin", "https://untrusted.example.test")
                .header("Access-Control-Request-Method", "DELETE")
                .exchange().expectStatus().isForbidden();
        assertThat(CALLS.get()).isZero();
    }

    @Test
    void givenStoreRejection__whenUpdateQuantity__thenPreserveErrorWithoutRetry() {
        client.patch().uri("/api/carts/items/42?quantity=-1").header("Authorization", "Bearer " + token())
                .exchange().expectStatus().isBadRequest();
        assertThat(CALLS.get()).isEqualTo(1);
    }

    private static String token() {
        return Jwts.builder().subject("cart-fixture@example.test").claim("userId", 7L)
                .claim("name", "fixture").claim("role", "ROLE_USER")
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(KEY.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
