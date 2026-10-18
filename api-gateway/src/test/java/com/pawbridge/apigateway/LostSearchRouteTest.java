package com.pawbridge.apigateway;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LostSearchRouteTest {
    static final AtomicReference<String> path = new AtomicReference<>();
    static final AtomicReference<String> body = new AtomicReference<>();
    static final AtomicReference<String> key = new AtomicReference<>();
    static final AtomicInteger calls = new AtomicInteger();
    static final HttpServer downstream = start();
    @Autowired WebTestClient web;

    private static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                calls.incrementAndGet(); path.set(exchange.getRequestURI().getPath());
                key.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = "{\"candidates\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start(); return server;
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("ANIMAL_SERVICE_URL", () -> "http://127.0.0.1:" + downstream.getAddress().getPort());
    }
    @AfterAll static void close() { downstream.stop(0); }

    @Test
    void givenAnonymousMultipart__whenSearching__thenReachExactAnimalRoute() {
        web.post().uri("/api/v1/animals/lost-candidates")
                .header("Content-Type", "multipart/form-data; boundary=test").header("X-Internal-Api-Key", "spoofed")
                .bodyValue("--test\r\nContent-Disposition: form-data; name=\"species\"\r\n\r\nDOG\r\n--test--\r\n")
                .exchange().expectStatus().isOk().expectBody().json("{\"candidates\":[]}");
        assertThat(path.get()).isEqualTo("/api/v1/animals/lost-candidates");
        assertThat(body.get()).contains("name=\"species\"", "DOG");
        assertThat(key.get()).isNull();
    }

    @Test
    void givenOtherWriteOrInternalPath__whenAnonymous__thenNeverReachAnimalService() {
        int before = calls.get();
        web.post().uri("/api/v1/animals").exchange().expectStatus().isUnauthorized();
        web.post().uri("/api/v1/animals/lost-candidates/extra").exchange().expectStatus().isUnauthorized();
        web.post().uri("/internal/animals/lost-candidates").exchange().expectStatus().isNotFound();
        assertThat(calls.get()).isEqualTo(before);
    }

    @Test
    void givenBrowserPreflight__whenSearching__thenAllowMultipartFromPawbridge() {
        web.options().uri("/api/v1/animals/lost-candidates").header("Origin", "https://pawbridge.kr")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type")
                .exchange().expectStatus().isOk().expectHeader().valueEquals("Access-Control-Allow-Origin", "https://pawbridge.kr");
    }
}
