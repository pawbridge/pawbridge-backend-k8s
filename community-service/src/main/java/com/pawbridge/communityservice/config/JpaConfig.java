package com.pawbridge.communityservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA 설정
 *
 * @EnableJpaAuditing:
 * - @CreatedDate, @LastModifiedDate 어노테이션 활성화
 * - Entity의 created_at, updated_at 자동 관리
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
