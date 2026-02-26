package com.pawbridge.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * API Gateway Security 설정
 * - CSRF 비활성화 (JWT 사용)
 * - CORS 설정은 application.yml의 globalcors 사용
 * - OPTIONS 요청 명시적 허용 (CORS preflight)
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
                
                // CORS 설정은 API Gateway의 globalcors 설정을 따르도록 Security에서 개입하지 않음 (기본값 유지)
                // .cors()를 명시하거나 disable 하지 않아야 Gateway의 CORS 필터가 정상 작동함

                // 요청 허용 설정
                .authorizeExchange(exchange -> exchange
                        // OPTIONS 요청 명시적 허용 (CORS preflight)
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 나머지 모든 요청 허용 (JWT Filter에서 검증)
                        .anyExchange().permitAll()
                );

        return http.build();
    }
}


