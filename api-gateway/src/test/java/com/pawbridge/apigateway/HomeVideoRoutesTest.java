package com.pawbridge.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.profiles.active=test", "server.address=127.0.0.1",
        "jwt.secret=synthetic-local-video-key-not-a-production-secret-12345678901234567890",
        "management.tracing.enabled=false"})
class HomeVideoRoutesTest {
    static final String KEY="synthetic-local-video-key-not-a-production-secret-12345678901234567890";
    static final AtomicInteger CALLS=new AtomicInteger();
    static final DisposableServer COMMUNITY=HttpServer.create().host("127.0.0.1").port(0).handle((request,response) -> {
        CALLS.incrementAndGet();
        return response.header("Content-Type","text/plain").sendString(Mono.just(request.method().name()+" "+request.uri()
                +"|"+request.requestHeaders().get("X-User-Role","")));
    }).bindNow(Duration.ofSeconds(10));
    @LocalServerPort int port;
    WebTestClient client;
    @DynamicPropertySource static void urls(DynamicPropertyRegistry registry) {
        for(String service:new String[]{"ANIMAL","USER","STORE","COMMUNITY","PAYMENT"})
            registry.add(service+"_SERVICE_URL",() -> "http://127.0.0.1:"+COMMUNITY.port());
    }
    @BeforeEach void setup() { CALLS.set(0);client=WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build(); }
    @AfterAll static void close() { COMMUNITY.disposeNow(); }
    static Stream<Arguments> writes() {
        return Stream.of("","/v1").flatMap(version -> Stream.of(
                Arguments.of("GET","/api"+version+"/admin/videos","/api/v1/admin/videos"),
                Arguments.of("POST","/api"+version+"/admin/videos","/api/v1/admin/videos"),
                Arguments.of("POST","/api"+version+"/admin/videos/preview","/api/v1/admin/videos/preview"),
                Arguments.of("PUT","/api"+version+"/admin/videos/order","/api/v1/admin/videos/order"),
                Arguments.of("PUT","/api"+version+"/admin/videos/id/publication","/api/v1/admin/videos/id/publication"),
                Arguments.of("POST","/api"+version+"/admin/videos/id/recheck","/api/v1/admin/videos/id/recheck")));
    }
    static String token(String role) {
        return Jwts.builder().setSubject("synthetic@example.invalid").claim("userId",7L).claim("name","합성")
                .claim("role",role).setIssuedAt(new Date()).setExpiration(new Date(System.currentTimeMillis()+60_000))
                .signWith(Keys.hmacShaKeyFor(KEY.getBytes(StandardCharsets.UTF_8))).compact();
    }
    @ParameterizedTest @ValueSource(strings={"/api/home/videos","/api/v1/home/videos"})
    void givenAnonymousOrStaleJwt_whenPublicList_thenForwardGetOnly(String path) {
        client.get().uri(path).header("Authorization","Bearer stale").exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo("GET /api/v1/home/videos|");
        assertThat(CALLS.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"/api/home/videos","/api/v1/home/videos","/api/home/videos/private","/api/v1/home/videos/private"})
    void givenPublicWriteOrSubpath_whenRequest_thenNotRouted(String path) {
        client.post().uri(path).exchange().expectStatus().isNotFound();
        assertThat(CALLS.get()).isZero();
    }
    @ParameterizedTest @MethodSource("writes")
    void givenAnonymous_whenAdminOperation_then401WithoutUpstream(String method,String path,String expected) {
        client.method(HttpMethod.valueOf(method)).uri(path).exchange().expectStatus().isUnauthorized();
        assertThat(CALLS.get()).isZero();
    }
    @ParameterizedTest @MethodSource("writes")
    void givenOrdinaryMemberAndForgedRole_whenAdminOperation_then403WithoutUpstream(String method,String path,String expected) {
        client.method(HttpMethod.valueOf(method)).uri(path).header("Authorization","Bearer "+token("ROLE_USER"))
                .header("X-User-Role","ROLE_ADMIN").exchange().expectStatus().isForbidden();
        assertThat(CALLS.get()).isZero();
    }
    @ParameterizedTest @MethodSource("writes")
    void givenAdmin_whenOperation_thenRewriteBeforeGuardAndForwardVerifiedRole(String method,String path,String expected) {
        client.method(HttpMethod.valueOf(method)).uri(path).header("Authorization","Bearer "+token("ROLE_ADMIN"))
                .header("X-User-Role","ROLE_USER").exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo(method+" "+expected+"|ROLE_ADMIN");
        assertThat(CALLS.get()).isEqualTo(1);
    }
    @Test void givenBrowserPreflight_whenAdminPost_thenCorsWithoutJwtOrUpstream() {
        client.options().uri("/api/admin/videos").header("Origin","https://www.pawbridge.kr")
                .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","authorization,content-type")
                .exchange().expectStatus().isOk().expectHeader().valueEquals("Access-Control-Allow-Origin","https://www.pawbridge.kr");
        assertThat(CALLS.get()).isZero();
    }
}
