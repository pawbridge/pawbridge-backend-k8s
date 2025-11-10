package com.pawbridge.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * API Gateway Security 설정
 * - CSRF 비활성화 (JWT 사용)
 * - 모든 요청 허용 (JWT Filter에서 인증 처리)
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                // CSRF 비활성화 (JWT 사용하므로 불필요)
                .csrf(ServerHttpSecurity.CsrfSpec::disable)

                // 모든 요청 허용 (JWT Filter에서 검증)
                .authorizeExchange(exchange -> exchange
                        .anyExchange().permitAll()
                );

        return http.build();
    }
}
