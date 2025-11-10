package com.pawbridge.apigateway.filter;

import com.pawbridge.apigateway.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * JWT 인가 필터
 * - Access Token 검증
 * - 사용자 정보 추출 및 헤더 추가
 */
@Slf4j
@Component
public class JwtAuthorizationGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JwtAuthorizationGatewayFilterFactory.Config> {

    private final JwtUtil jwtUtil;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    // 토큰 검증이 필요 없는 경로 (화이트리스트)
    private static final List<String> WHITELIST = List.of(
            "/api/user/sign-up",    // 회원가입
            "/api/user/login",      // 로그인
            "/api/auth/refresh"     // 토큰 재발급 (Access Token 만료 시 사용)
    );

    public JwtAuthorizationGatewayFilterFactory(JwtUtil jwtUtil) {
        super(Config.class);
        this.jwtUtil = jwtUtil;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String path = request.getURI().getPath();

            // 화이트리스트 경로는 토큰 검증 스킵
            if (isWhitelisted(path)) {
                log.info("화이트리스트 경로: {}", path);
                return chain.filter(exchange);
            }

            // Authorization 헤더에서 토큰 추출
            String token = extractToken(request);

            if (token == null) {
                log.warn("Authorization 헤더 없음: {}", path);
                return onError(exchange, "Missing Authorization header", HttpStatus.UNAUTHORIZED);
            }

            // Access Token 검증
            if (!jwtUtil.validateAccessToken(token)) {
                log.warn("유효하지 않은 토큰: {}", path);
                return onError(exchange, "Invalid or expired token", HttpStatus.UNAUTHORIZED);
            }

            // 토큰에서 사용자 정보 추출
            try {
                Long userId = jwtUtil.getUserIdFromToken(token);
                String email = jwtUtil.getEmailFromToken(token);
                String name = jwtUtil.getNameFromToken(token);
                String role = jwtUtil.getRoleFromToken(token);

                // Authorization 헤더는 유지하고, X-User-* 헤더 추가
                // (외부 서비스 호출 시 원본 토큰 필요할 수 있음)
                ServerHttpRequest modifiedRequest = request.mutate()
                        .header("X-User-Id", userId.toString())
                        .header("X-User-Email", email)
                        .header("X-User-Name", name)
                        .header("X-User-Role", role)
                        .build();

                log.info("JWT 검증 성공 - userId: {}, email: {}, role: {}, path: {}", userId, email, role, path);

                return chain.filter(exchange.mutate().request(modifiedRequest).build());

            } catch (Exception e) {
                log.error("토큰 파싱 실패: {}", e.getMessage());
                return onError(exchange, "Failed to parse token", HttpStatus.UNAUTHORIZED);
            }
        };
    }

    /**
     * Authorization 헤더에서 Bearer 토큰 추출
     */
    private String extractToken(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        return null;
    }

    /**
     * 화이트리스트 경로 확인 (와일드카드 지원)
     */
    private boolean isWhitelisted(String path) {
        return WHITELIST.stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    /**
     * 에러 응답
     */
    private Mono<Void> onError(ServerWebExchange exchange, String message, HttpStatus status) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);

        log.error("JWT 인가 실패 - message: {}, status: {}, path: {}",
                message, status, exchange.getRequest().getURI().getPath());

        return response.setComplete();
    }

    /**
     * 설정 클래스
     */
    public static class Config {
        // 필요시 설정값 추가
    }
}
