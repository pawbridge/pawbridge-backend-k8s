package com.pawbridge.apigateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.apigateway.util.ErrorResponse;
import com.pawbridge.apigateway.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.cors.reactive.CorsUtils;
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 토큰 검증이 필요 없는 경로.
     * 조회용 공개 경로와 쓰기 경로가 같은 path를 공유할 수 있으므로 HTTP 메서드까지 함께 검증한다.
     */
    private static final List<String> WHITELIST = List.of(
            "POST:/api/v1/users/signup",
            "POST:/api/v1/auth/login",
            "POST:/api/v1/auth/refresh",
            "POST:/api/v1/auth/password/reset-request",
            "POST:/api/v1/auth/password/reset",
            "GET:/api/v1/posts/read/*",
            "GET:/api/v1/posts/read",
            "GET:/api/v1/posts/search",
            "GET:/api/v1/comments/posts/read/*",
            "GET:/api/v1/animals",
            "GET:/api/v1/animals/*",
            "GET:/api/v1/animals/expiring-soon",
            "GET:/api/v1/shelters",
            "GET:/api/v1/shelters/*"
    );

    // ROLE_ADMIN만 접근 가능한 경로
    private static final List<String> ADMIN_ONLY_PATHS = List.of(
            "POST:/api/shelters",
            "DELETE:/api/shelters/*",
            "POST:/api/v1/shelters",
            "DELETE:/api/v1/shelters/*",
            "POST:/api/products",
            "PATCH:/api/products/*",
            "DELETE:/api/products/*",
            "POST:/api/images",
            "POST:/api/categories",
            "PUT:/api/categories/*",
            "DELETE:/api/categories/*",
            "POST:/api/option-groups",
            "PUT:/api/option-groups/*",
            "DELETE:/api/option-groups/*",
            "POST:/api/option-groups/*/values",
            "PUT:/api/option-groups/values/*",
            "DELETE:/api/option-groups/values/*",
            "GET:/api/admin/orders",
            "PATCH:/api/admin/orders/*/status",
            "PATCH:/api/admin/orders/*/delivery-status"
    );

    // ROLE_USER가 아닐 때 접근 가능한 경로 (ROLE_ADMIN, ROLE_SHELTER)
    private static final List<String> NON_USER_PATHS = List.of(
            "PUT:/api/shelters/*",
            "PATCH:/api/shelters/*",
            "POST:/api/animals",
            "POST:/api/animals/*",
            "PUT:/api/animals/*",
            "PATCH:/api/animals/*",
            "DELETE:/api/animals/*",
            "PUT:/api/v1/shelters/*",
            "PATCH:/api/v1/shelters/*",
            "POST:/api/v1/animals",
            "POST:/api/v1/animals/*",
            "PUT:/api/v1/animals/*",
            "PATCH:/api/v1/animals/*",
            "DELETE:/api/v1/animals/*"
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
            String method = request.getMethod().name();

            // 실제 CORS preflight 요청만 토큰 검증 없이 통과
            if (CorsUtils.isPreFlightRequest(request)) {
                return chain.filter(exchange);
            }

            // 화이트리스트 경로는 토큰 검증 스킵
            if (isWhitelisted(method, path)) {
                log.info("화이트리스트 경로: {} {}", method, path);
                return chain.filter(exchange);
            }

            // Authorization 헤더에서 토큰 추출
            String token = extractToken(request);

            if (token == null) {
                log.warn("Authorization 헤더 없음: {}", path);
                return onError(exchange, "인증 토큰이 필요합니다.", HttpStatus.UNAUTHORIZED);
            }

            if (!jwtUtil.validateAccessToken(token)) {
                log.warn("유효하지 않은 토큰: {}", path);
                return onError(exchange, "유효하지 않거나 만료된 토큰입니다.", HttpStatus.UNAUTHORIZED);
            }

            // 토큰에서 사용자 정보 추출
            try {
                Long userId = jwtUtil.getUserIdFromToken(token);
                String email = jwtUtil.getEmailFromToken(token);
                String name = jwtUtil.getNameFromToken(token);
                String role = jwtUtil.getRoleFromToken(token);

                // ADMIN만 접근 가능한 경로 체크
                if (isAdminOnlyPath(method, path) && !role.equals("ROLE_ADMIN")) {
                    log.warn("관리자 전용 경로 접근 거부 - role: {}, path: {}", role, path);
                    return onError(exchange, "관리자 권한이 필요합니다.", HttpStatus.FORBIDDEN);
                }

                // ROLE_ADMIN, ROLE_SHELTER만 접근 가능한 경로 체크
                if (isNonUserPath(method, path) && role.equals("ROLE_USER")) {
                    log.warn("권한 부족 - role: {}, path: {}", role, path);
                    return onError(exchange, "권한이 부족합니다.", HttpStatus.FORBIDDEN);
                }

                // Authorization 헤더는 유지하고, X-User-* 헤더 추가
                var requestBuilder = request.mutate()
                        .header("X-User-Id", userId.toString())
                        .header("X-User-Email", email)
                        .header("X-User-Name", name)
                        .header("X-User-Role", role);

                // ROLE_SHELTER인 경우 careRegNo 헤더 추가
                if ("ROLE_SHELTER".equals(role)) {
                    String careRegNo = jwtUtil.getCareRegNoFromToken(token);
                    if (careRegNo != null && !careRegNo.isBlank()) {
                        requestBuilder.header("X-Care-Reg-No", careRegNo);
                        log.info("JWT 검증 성공 - userId: {}, email: {}, role: {}, careRegNo: {}, path: {}",
                                userId, email, role, careRegNo, path);
                    } else {
                        log.info("JWT 검증 성공 - userId: {}, email: {}, role: {}, path: {}", userId, email, role, path);
                    }
                } else {
                    log.info("JWT 검증 성공 - userId: {}, email: {}, role: {}, path: {}", userId, email, role, path);
                }

                ServerHttpRequest modifiedRequest = requestBuilder.build();

                return chain.filter(exchange.mutate().request(modifiedRequest).build());

            } catch (Exception e) {
                log.error("토큰 파싱 실패: {}", e.getMessage());
                return onError(exchange, "토큰 파싱에 실패했습니다.", HttpStatus.UNAUTHORIZED);
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
     * 화이트리스트 경로 확인 (HTTP 메서드와 경로를 함께 매칭)
     */
    private boolean isWhitelisted(String method, String path) {
        return WHITELIST.stream()
                .anyMatch(pattern -> matchesMethodAndPath(pattern, method, path));
    }

    /**
     * ADMIN만 접근 가능한 경로인지 확인
     */
    private boolean isAdminOnlyPath(String method, String path) {
        // /api/v1/admin/** 패턴은 모든 HTTP 메서드에 대해 ADMIN 권한 필요
        if (pathMatcher.match("/api/v1/admin/**", path)) {
            return true;
        }

        return ADMIN_ONLY_PATHS.stream()
                .anyMatch(pattern -> matchesMethodAndPath(pattern, method, path));
    }

    /**
     * ROLE_USER가 아닐 때 접근 가능한 경로인지 확인
     */
    private boolean isNonUserPath(String method, String path) {
        return NON_USER_PATHS.stream()
                .anyMatch(pattern -> matchesMethodAndPath(pattern, method, path));
    }

    /**
     * HTTP 메서드와 경로를 함께 매칭 (와일드카드 지원)
     * @param pattern "METHOD:/path/pattern" 형식
     * @param method 요청 HTTP 메서드
     * @param path 요청 경로
     */
    private boolean matchesMethodAndPath(String pattern, String method, String path) {
        String[] parts = pattern.split(":", 2);
        if (parts.length != 2) {
            return false;
        }
        String patternMethod = parts[0];
        String patternPath = parts[1];
        return patternMethod.equals(method) && pathMatcher.match(patternPath, path);
    }

    /**
     * 에러 응답 (JSON 형식)
     */
    private Mono<Void> onError(ServerWebExchange exchange, String message, HttpStatus status) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        log.error("JWT 인가 실패 - message: {}, status: {}, path: {}",
                message, status, exchange.getRequest().getURI().getPath());

        // 다른 서비스의 ResponseDTO와 동일한 구조로 에러 응답 생성
        ErrorResponse errorResponse = ErrorResponse.of(status.value(), message);

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(errorResponse);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            log.error("JSON 변환 실패: {}", e.getMessage());
            return response.setComplete();
        }
    }

    /**
     * 설정 클래스
     */
    public static class Config {
        // 필요시 설정값 추가
    }
}
