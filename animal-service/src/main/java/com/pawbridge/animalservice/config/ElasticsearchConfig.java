package com.pawbridge.animalservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

/**
 * Elasticsearch 설정
 * - Spring Data Elasticsearch Repository 활성화
 * - 연결 설정은 application.yml에서 관리
 */
@Configuration
@EnableElasticsearchRepositories(basePackages = "com.pawbridge.animalservice.repository")
public class ElasticsearchConfig {
    // Spring Boot Auto-configuration이 ElasticsearchClient 및 ElasticsearchTemplate을 자동 생성
    // application.yml의 spring.elasticsearch.uris 설정 사용
}
