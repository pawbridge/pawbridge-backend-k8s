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
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/** Production route predicates, filter order and CORS; Store is a loopback fixture. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=test", "server.address=127.0.0.1",
        "jwt.secret=synthetic-local-test-key-not-a-production-secret-12345678901234567890",
        "management.tracing.enabled=false"
})
class StoreAdminRoutesTest {
    private static final String KEY = "synthetic-local-test-key-not-a-production-secret-12345678901234567890";
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final DisposableServer STORE = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> {
                CALLS.incrementAndGet();
                int status = request.path().endsWith("/404") ? 404
                        : request.path().endsWith("/503") ? 503 : 200;
                return response.status(status).header("Content-Type", "text/plain")
                        .sendString(Mono.just(request.method().name() + " " + request.uri()
                                + "|" + request.requestHeaders().get("X-User-Id", "")
                                + "|" + request.requestHeaders().get("X-User-Role", "")));
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

    private static Stream<Arguments> protectedOperations() {
        return Stream.of(
                Arguments.of("POST", "/api/option-groups", "/api/v1/option-groups"),
                Arguments.of("PUT", "/api/option-groups/7", "/api/v1/option-groups/7"),
                Arguments.of("DELETE", "/api/option-groups/7", "/api/v1/option-groups/7"),
                Arguments.of("POST", "/api/option-groups/7/values", "/api/v1/option-groups/7/values"),
                Arguments.of("PUT", "/api/option-groups/values/8", "/api/v1/option-groups/values/8"),
                Arguments.of("DELETE", "/api/option-groups/values/8", "/api/v1/option-groups/values/8"),
                Arguments.of("GET", "/api/admin/orders?page=0&keyword=a%2Bb&size=10", "/api/v1/admin/orders?page=0&keyword=a%2Bb&size=10"),
                // Gateway must protect detail even though the current Store has no detail handler.
                Arguments.of("GET", "/api/admin/orders/42", "/api/v1/admin/orders/42"),
                Arguments.of("PATCH", "/api/admin/orders/42/status", "/api/v1/admin/orders/42/status"),
                Arguments.of("PATCH", "/api/admin/orders/42/delivery-status", "/api/v1/admin/orders/42/delivery-status"));
    }

    private static Stream<Arguments> nonAdminOperations() {
        return Stream.of("ROLE_USER", "ROLE_SHELTER").flatMap(role -> protectedOperations()
                .map(operation -> Arguments.of(operation.get()[0], operation.get()[1], role)));
    }

    private static Stream<Arguments> preflightOperations() {
        return Stream.concat(Stream.of(
                Arguments.of("GET", "/api/option-groups", ""),
                Arguments.of("GET", "/api/option-groups/7", "")), protectedOperations());
    }

    @ParameterizedTest
    @CsvSource({
            "/api/option-groups?extra=a%2Bb&extra=c, /api/v1/option-groups?extra=a%2Bb&extra=c",
            "/api/option-groups/7, /api/v1/option-groups/7"
    })
    void givenAnonymousOptionRead__whenBrowse__thenRewriteAndPreserveRawQuery(String path, String expected) {
        client.get().uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Origin", "https://www.pawbridge.kr")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://www.pawbridge.kr")
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true")
                .expectBody(String.class).isEqualTo("GET " + expected + "||");
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    void givenStaleToken__whenReadPublicOptions__thenNoLoginRequired() {
        client.get().uri("/api/option-groups").header("Authorization", "Bearer synthetic-invalid-token")
                .exchange().expectStatus().isOk();
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("protectedOperations")
    void givenAnonymous__whenProtectedOperation__then401WithoutCallingStore(String method, String path, String expected) {
        client.method(HttpMethod.valueOf(method)).uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Origin", "https://www.pawbridge.kr")
                .exchange().expectStatus().isUnauthorized()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://www.pawbridge.kr");
        assertThat(CALLS.get()).isZero();
    }

    @ParameterizedTest
    @MethodSource("nonAdminOperations")
    void givenNonAdmin__whenProtectedOperation__then403WithoutCallingStore(String method, String path, String role) {
        client.method(HttpMethod.valueOf(method)).uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token(role))
                .header("X-User-Role", "ROLE_ADMIN")
                .exchange().expectStatus().isForbidden();
        assertThat(CALLS.get()).isZero();
    }

    @ParameterizedTest
    @MethodSource("protectedOperations")
    void givenAdmin__whenProtectedOperation__thenForwardOnceWithVerifiedIdentity(String method, String path, String expected) {
        client.method(HttpMethod.valueOf(method)).uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token("ROLE_ADMIN"))
                .header("X-User-Id", "999").header("X-User-Role", "ROLE_USER")
                .exchange().expectStatus().isOk().expectBody(String.class)
                .isEqualTo(method + " " + expected + "|7|ROLE_ADMIN");
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"PUT, /api/option-groups, ROLE_USER", "POST, /api/option-groups/7, ROLE_SHELTER"})
    void givenUnsupportedOptionWrite__whenNonAdmin__thenNoAuthorizationBypass(String method, String path, String role) {
        client.method(HttpMethod.valueOf(method)).uri(path)
                .header("Authorization", "Bearer " + token(role))
                .exchange().expectStatus().isForbidden();
        assertThat(CALLS.get()).isZero();
    }

    @ParameterizedTest
    @MethodSource("preflightOperations")
    void givenFrontendPreflight__whenRequestOperation__thenAllowWithoutAuthenticationOrStoreCall(String method, String path, String expected) {
        client.options().uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Origin", "https://www.pawbridge.kr")
                .header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", "authorization,content-type,x-user-id")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://www.pawbridge.kr")
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true");
        assertThat(CALLS.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/option-groups", "/api/admin/orders"})
    void givenUntrustedOrigin__whenPreflight__thenRejectWithoutCallingStore(String path) {
        client.options().uri(path).header("Origin", "https://untrusted.example.test")
                .header("Access-Control-Request-Method", "GET")
                .exchange().expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
        assertThat(CALLS.get()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"POST, /api/option-groups", "GET, /api/admin/orders"})
    void givenInvalidToken__whenProtectedOperation__then401WithoutCallingStore(String method, String path) {
        client.method(HttpMethod.valueOf(method)).uri(path).header("Authorization", "Bearer synthetic-invalid-token")
                .exchange().expectStatus().isUnauthorized();
        assertThat(CALLS.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 503})
    void givenStoreError__whenAdminOrderRead__thenPreserveStatusWithoutRetry(int status) {
        client.get().uri("/api/admin/orders/" + status)
                .header("Authorization", "Bearer " + token("ROLE_ADMIN"))
                .exchange().expectStatus().isEqualTo(status);
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/option-groups-extra", "/api/option-groups/7/values", "/api/admin/orders-extra"})
    void givenOutsideReadContract__whenGet__thenNoRouteOrStoreCall(String path) {
        client.get().uri(path).exchange().expectStatus().isNotFound();
        assertThat(CALLS.get()).isZero();
    }

    private static String token(String role) {
        return Jwts.builder().subject("fixture@example.test").claim("userId", 7L)
                .claim("name", "fixture").claim("role", role)
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(KEY.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
