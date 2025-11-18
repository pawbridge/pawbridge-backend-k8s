package com.pawbridge.animalservice.config;

import feign.Logger.Level;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FeignClient 설정
 */
@Configuration
@EnableFeignClients(basePackages = "com.pawbridge.animalservice.client")
public class FeignConfig {

    /**
     * Feign 로깅 레벨 설정
     * - NONE: 로깅 없음
     * - BASIC: 요청 메소드, URL, 응답 상태 코드, 실행 시간
     * - HEADERS: BASIC + 요청/응답 헤더
     * - FULL: HEADERS + 요청/응답 본문
     */
    @Bean
    public Level feignLoggerLevel() {
        return Level.FULL;
    }
}
