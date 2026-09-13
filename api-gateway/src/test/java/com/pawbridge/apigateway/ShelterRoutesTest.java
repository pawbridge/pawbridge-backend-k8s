package com.pawbridge.apigateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

/** Real gateway configuration and JWT filter; downstream services are loopback fixtures. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=test", "server.address=127.0.0.1",
        "jwt.secret=synthetic-local-test-key-not-a-production-secret-12345678901234567890",
        "management.tracing.enabled=false"
})
class ShelterRoutesTest {
    private static final String KEY = "synthetic-local-test-key-not-a-production-secret-12345678901234567890";
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final DisposableServer UPSTREAM = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> {
                CALLS.incrementAndGet();
                return response.header("Content-Type", "text/plain")
                        .sendString(Mono.just(request.method().name() + " " + request.uri()
                                + "|" + request.requestHeaders().get("Authorization", "")
                                + "|" + request.requestHeaders().get("X-User-Id", "")));
            }).bindNow(Duration.ofSeconds(10));
    @LocalServerPort private int port;
    private WebTestClient client;

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        for (var service : new String[]{"ANIMAL", "USER", "STORE", "COMMUNITY", "PAYMENT"}) {
            registry.add(service + "_SERVICE_URL", () -> "http://127.0.0.1:" + UPSTREAM.port());
        }
    }
    @BeforeEach void setup() {
        CALLS.set(0);
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(5)).build();
    }
    @AfterAll static void close() { UPSTREAM.disposeNow(); }

    private String token(String role) {
        return Jwts.builder().subject("fixture@example.test").claim("userId", 7L)
                .claim("name", "fixture").claim("role", role)
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(KEY.getBytes(StandardCharsets.UTF_8))).compact();
    }

    @ParameterizedTest
    @CsvSource({
        "GET, /api/users/me/shelter-applications?page=0&size=10, /api/v1/users/me/shelter-applications?page=0&size=10, ROLE_USER",
        "POST, /api/users/me/shelter-applications, /api/v1/users/me/shelter-applications, ROLE_USER",
        "GET, /api/admin/users/shelter-applications?status=PENDING&page=0, /api/v1/admin/users/shelter-applications?status=PENDING&page=0, ROLE_ADMIN",
        "GET, /api/admin/users/shelter-applications/12, /api/v1/admin/users/shelter-applications/12, ROLE_ADMIN",
        "POST, /api/admin/users/shelter-applications/12/approve, /api/v1/admin/users/shelter-applications/12/approve, ROLE_ADMIN",
        "POST, /api/admin/users/shelter-applications/12/reject, /api/v1/admin/users/shelter-applications/12/reject, ROLE_ADMIN",
        "GET, /api/admin/users/shelters/123/members?page=0&size=20, /api/v1/admin/users/shelters/123/members?page=0&size=20, ROLE_ADMIN"
    })
    void authenticatedRequestPreservesRouteQueryAndIdentity(String method, String path, String expected, String role) {
        String authorization = "Bearer " + token(role);
        client.method(HttpMethod.valueOf(method)).uri(path).header("Authorization", authorization)
                .exchange().expectStatus().isOk().expectBody(String.class)
                .isEqualTo(method + " " + expected + "|" + authorization + "|7");
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({
        "/api/shelters?keyword=test&page=0, /api/v1/shelters?keyword=test&page=0",
        "/api/shelters/by-care-reg-no/123, /api/v1/shelters/by-care-reg-no/123"
    })
    void shelterReadsRemainPublic(String path, String expected) {
        client.get().uri(path).exchange().expectStatus().isOk().expectBody(String.class)
                .isEqualTo("GET " + expected + "||");
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({
        "GET, /api/users/me/shelter-applications",
        "POST, /api/users/me/shelter-applications",
        "GET, /api/admin/users/shelter-applications",
        "POST, /api/admin/users/shelter-applications/12/approve",
        "GET, /api/admin/users/shelters/123/members"
    })
    void missingAuthenticationNeverReachesService(String method, String path) {
        client.method(HttpMethod.valueOf(method)).uri(path).exchange().expectStatus().isUnauthorized();
        assertThat(CALLS.get()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "GET, /api/admin/users/shelter-applications",
        "POST, /api/admin/users/shelter-applications/12/approve",
        "POST, /api/admin/users/shelter-applications/12/reject",
        "GET, /api/admin/users/shelters/123/members"
    })
    void ordinaryMemberCannotReachAdminService(String method, String path) {
        client.method(HttpMethod.valueOf(method)).uri(path).header("Authorization", "Bearer " + token("ROLE_USER"))
                .exchange().expectStatus().isForbidden();
        assertThat(CALLS.get()).isZero();
    }
}
