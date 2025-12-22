package com.pawbridge.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * API Gateway Security 설정
 * - CSRF 비활성화 (JWT 사용)
 * - CORS 설정은 application.yml의 globalcors 사용
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
                
                // CORS는 application.yml의 spring.cloud.gateway.globalcors 설정 사용
                // Security 레벨에서는 비활성화
                .cors(ServerHttpSecurity.CorsSpec::disable)

                // 모든 요청 허용 (JWT Filter에서 검증)
                .authorizeExchange(exchange -> exchange
                        .anyExchange().permitAll()
                );

        return http.build();
    }
}

