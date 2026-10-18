package com.pawbridge.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.profiles.active=test","server.address=127.0.0.1",
        "jwt.secret="+HomeVideoRoutesTest.KEY,"management.tracing.enabled=false"})
class VisitRoutesTest {
    static final AtomicInteger CALLS=new AtomicInteger();
    static final DisposableServer COMMUNITY=HttpServer.create().host("127.0.0.1").port(0).handle((request,response) -> {
        CALLS.incrementAndGet();
        String identity=java.util.List.of("Authorization","X-User-Id","X-User-Email","X-User-Name","X-User-Role").stream()
                .map(name -> request.requestHeaders().get(name,"")).collect(java.util.stream.Collectors.joining(","));
        return response.sendString(Mono.just(request.method().name()+" "+request.uri()+"|"+identity));
    }).bindNow(Duration.ofSeconds(10));
    @LocalServerPort int port;
    WebTestClient client;
    @DynamicPropertySource static void urls(DynamicPropertyRegistry registry) {
        for(String service:new String[]{"ANIMAL","USER","STORE","COMMUNITY","PAYMENT"})
            registry.add(service+"_SERVICE_URL",() -> "http://127.0.0.1:"+COMMUNITY.port());
    }
    @BeforeEach void setup() { CALLS.set(0);client=WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build(); }
    @AfterAll static void close() { COMMUNITY.disposeNow(); }

    @Test void givenAnonymousAndInjectedIdentity_whenPublicCount_thenExactRewriteAndStripIdentity() {
        for (String prefix:java.util.List.of("/api","/api/v1"))
            client.post().uri(prefix+"/visits/today").header("Authorization","Bearer stale").header("X-User-Id","123")
                    .header("X-User-Email","fake@example.invalid").header("X-User-Name","fake").header("X-User-Role","ROLE_ADMIN")
                    .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("POST /api/v1/visits/today|,,,,");
    }
    @Test void givenUnsupportedMethodOrPath_whenPublic_thenNeverForward() {
        client.put().uri("/api/visits/today").exchange().expectStatus().isNotFound();
        client.get().uri("/api/visits/identity").exchange().expectStatus().isNotFound();
        client.post().uri("/api/visits/other").exchange().expectStatus().isNotFound();
        assertThat(CALLS.get()).isZero();
    }
    @Test void givenNoTokenOrNonAdmin_whenStats_thenUnauthorizedOrForbiddenDespiteForgedRole() {
        String path="/api/admin/visits/stats/period?startDate=2026-10-12&endDate=2026-10-12";
        client.get().uri(path).header("X-User-Role","ROLE_ADMIN").exchange().expectStatus().isUnauthorized();
        client.get().uri(path).header("Authorization","Bearer "+HomeVideoRoutesTest.token("ROLE_USER"))
                .header("X-User-Role","ROLE_ADMIN").exchange().expectStatus().isForbidden();
        assertThat(CALLS.get()).isZero();
    }
    @Test void givenAdmin_whenStats_thenVerifiedRoleAndQueryPreserved() {
        client.get().uri("/api/admin/visits/stats/period?startDate=2026-10-12&endDate=2026-10-12")
                .header("Authorization","Bearer "+HomeVideoRoutesTest.token("ROLE_ADMIN"))
                .exchange().expectStatus().isOk().expectBody(String.class)
                .value(value -> assertThat(value).contains("GET /api/v1/admin/visits/stats/period?startDate=2026-10-12&endDate=2026-10-12","ROLE_ADMIN"));
    }
}
