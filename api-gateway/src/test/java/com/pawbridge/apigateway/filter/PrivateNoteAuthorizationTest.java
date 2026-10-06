package com.pawbridge.apigateway.filter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pawbridge.apigateway.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.util.concurrent.atomic.AtomicReference;

class PrivateNoteAuthorizationTest {
    final JwtUtil jwt=mock(JwtUtil.class);
    final JwtAuthorizationGatewayFilterFactory filter=new JwtAuthorizationGatewayFilterFactory(jwt);
    @ParameterizedTest @ValueSource(strings={"/api/v1/notes","/api/v1/notes/notifications","/api/v1/notes/stream","/api/v1/notes/blocks"})
    void givenAnonymousRequest_whenMailboxAccess_thenUnauthorized(String path) {
        var request=MockServerWebExchange.from(MockServerHttpRequest.get(path));
        filter.apply(new JwtAuthorizationGatewayFilterFactory.Config()).filter(request,e -> {throw new AssertionError("must not forward");}).block();
        assertThat(request.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void givenForgedIdentityAndExpiry_whenAuthenticatedStream_thenOverwriteWithJwtClaims() {
        when(jwt.validateAccessToken("synthetic-token")).thenReturn(true);
        when(jwt.getUserIdFromToken("synthetic-token")).thenReturn(7L);
        when(jwt.getRoleFromToken("synthetic-token")).thenReturn("ROLE_USER");
        when(jwt.getEmailFromToken("synthetic-token")).thenReturn("synthetic@example.invalid");
        when(jwt.getNameFromToken("synthetic-token")).thenReturn("합성 사용자");
        when(jwt.getExpiresAtFromToken("synthetic-token")).thenReturn(123456789L);
        var request=MockServerWebExchange.from(streamRequest());
        var forwarded=new AtomicReference<ServerWebExchange>();
        filter.apply(new JwtAuthorizationGatewayFilterFactory.Config()).filter(request,e -> {forwarded.set(e);return Mono.empty();}).block();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("7");
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-Auth-Expires-At")).isEqualTo("123456789");
    }
    private org.springframework.mock.http.server.reactive.MockServerHttpRequest streamRequest() {
        return MockServerHttpRequest.get("/api/v1/notes/stream").header("Authorization","Bearer synthetic-token")
                .header("X-User-Id","999").header("X-Auth-Expires-At","9999999999999").build();
    }
    @ParameterizedTest @ValueSource(strings={"/api/v1/users/internal/7/contact","/api/users/internal/7/contact"})
    void givenInternalContactPath_whenPublicRequest_thenNotFound(String path) {
        var request=MockServerWebExchange.from(MockServerHttpRequest.get(path));
        filter.apply(new JwtAuthorizationGatewayFilterFactory.Config()).filter(request,e -> {throw new AssertionError("internal route");}).block();
        assertThat(request.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
