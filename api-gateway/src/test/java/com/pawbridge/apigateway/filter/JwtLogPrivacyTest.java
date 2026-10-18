package com.pawbridge.apigateway.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pawbridge.apigateway.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtLogPrivacyTest {
    private final JwtUtil jwt = mock(JwtUtil.class);
    private final JwtAuthorizationGatewayFilterFactory factory = new JwtAuthorizationGatewayFilterFactory(jwt);
    private final Logger logger = (Logger) LoggerFactory.getLogger(JwtAuthorizationGatewayFilterFactory.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;
    private static final String TOKEN = "synthetic-private-token";
    private static final String EMAIL = "private-person@example.test";

    @BeforeEach void capture() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
    }
    @AfterEach void close() {
        logger.detachAppender(logs);
        logs.stop();
        logger.setLevel(previousLevel);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"})
    void givenValidIdentity_whenAuthorized_thenHeadersArePreservedWithoutLoggingPersonalFields(String role) {
        when(jwt.validateAccessToken(TOKEN)).thenReturn(true);
        when(jwt.getUserIdFromToken(TOKEN)).thenReturn(7L);
        when(jwt.getEmailFromToken(TOKEN)).thenReturn(EMAIL);
        when(jwt.getNameFromToken(TOKEN)).thenReturn("PrivateName");
        when(jwt.getRoleFromToken(TOKEN)).thenReturn(role);
        if (role.equals("ROLE_SHELTER")) when(jwt.getCareRegNoFromToken(TOKEN)).thenReturn("private-care-no");
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/users/me")
                .header("Authorization", "Bearer " + TOKEN));
        var forwarded = new AtomicReference<ServerWebExchange>();
        factory.apply(new JwtAuthorizationGatewayFilterFactory.Config()).filter(exchange, request -> {
            forwarded.set(request);
            return Mono.empty();
        }).block();

        var headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(headers.getFirst("X-User-Id")).isEqualTo("7");
        assertThat(headers.getFirst("X-User-Email")).isEqualTo(EMAIL);
        assertThat(headers.getFirst("X-User-Name")).isEqualTo("PrivateName");
        assertThat(headers.getFirst("X-User-Role")).isEqualTo(role);
        if (role.equals("ROLE_SHELTER")) assertThat(headers.getFirst("X-Care-Reg-No")).isEqualTo("private-care-no");
        assertThat(logs.list).isNotEmpty().allSatisfy(event ->
                assertThat(event.getFormattedMessage()).doesNotContain(EMAIL, TOKEN, "PrivateName", "private-care-no"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid", "forbidden", "parse"})
    void givenRejectedRequest_whenFiltered_thenOneWarningAndUnchangedStatusWithoutForwarding(String scenario) {
        var request = MockServerHttpRequest.post("/api/products");
        if (!scenario.equals("missing")) request.header("Authorization", "Bearer " + TOKEN);
        if (scenario.equals("forbidden") || scenario.equals("parse")) {
            when(jwt.validateAccessToken(TOKEN)).thenReturn(true);
            if (scenario.equals("parse")) {
                when(jwt.getUserIdFromToken(TOKEN)).thenThrow(new IllegalArgumentException(EMAIL + " " + TOKEN));
            } else {
                when(jwt.getUserIdFromToken(TOKEN)).thenReturn(7L);
                when(jwt.getEmailFromToken(TOKEN)).thenReturn(EMAIL);
                when(jwt.getNameFromToken(TOKEN)).thenReturn("PrivateName");
                when(jwt.getRoleFromToken(TOKEN)).thenReturn("ROLE_USER");
            }
        }
        var exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        factory.apply(new JwtAuthorizationGatewayFilterFactory.Config()).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(scenario.equals("forbidden") ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(chain);
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).doesNotContain(EMAIL, TOKEN);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }
}
