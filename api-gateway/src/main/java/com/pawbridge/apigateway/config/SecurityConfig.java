package com.pawbridge.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * API Gateway Security 설정
 * - CSRF 비활성화 (JWT 사용)
 * - CORS 설정 (프론트엔드 요청 허용)
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
                
                // CORS 활성화 (corsConfigurationSource Bean 사용)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // 모든 요청 허용 (JWT Filter에서 검증)
                .authorizeExchange(exchange -> exchange
                        .anyExchange().permitAll()
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // 허용할 Origin 패턴
        configuration.setAllowedOriginPatterns(Arrays.asList(
                "https://pawbridge.kr",
                "https://www.pawbridge.kr",
                "https://*.pawbridge.kr",
                "http://localhost:3000",
                "http://localhost:5173"
        ));
        
        // 허용할 HTTP 메서드
        configuration.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"
        ));
        
        // 허용할 헤더
        configuration.setAllowedHeaders(Arrays.asList(
                "Authorization", "Content-Type", "Accept"
        ));
        
        // 노출할 헤더
        configuration.setExposedHeaders(List.of("Authorization"));
        
        // 자격 증명 허용 여부
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
