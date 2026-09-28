package com.pawbridge.apigateway;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=test", "server.address=127.0.0.1",
        "jwt.secret=synthetic-local-test-key-not-a-production-secret-12345678901234567890",
        "management.tracing.enabled=false"
})
class AnimalReportRoutesTest {
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final DisposableServer UPSTREAM = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> {
                CALLS.incrementAndGet();
                return response.sendString(Mono.just(request.uri()));
            }).bindNow(Duration.ofSeconds(10));

    @LocalServerPort int port;
    WebTestClient client;

    @DynamicPropertySource
    static void localUpstream(DynamicPropertyRegistry registry) {
        registry.add("COMMUNITY_SERVICE_URL", () -> "http://127.0.0.1:" + UPSTREAM.port());
    }

    @BeforeEach
    void setUp() {
        CALLS.set(0);
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    @AfterAll
    static void closeUpstream() { UPSTREAM.disposeNow(); }

    @ParameterizedTest
    @CsvSource({
            "/api/reports, /api/v1/reports",
            "/api/reports/12, /api/v1/reports/12",
            "/api/v1/reports/12, /api/v1/reports/12"
    })
    void givenAnonymousReader_whenReadingReports_thenRewriteOnceAndForward(String path, String expected) {
        client.get().uri(path).exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo(expected);
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    void givenAnonymousWriter_whenCreatingReport_thenRejectBeforeUpstream() {
        client.post().uri("/api/reports").exchange().expectStatus().isUnauthorized();
        assertThat(CALLS.get()).isZero();
    }
}
