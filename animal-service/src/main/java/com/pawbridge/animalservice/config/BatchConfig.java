package com.pawbridge.animalservice.config;

import org.springframework.context.annotation.Configuration;

/**
 * Spring Batch 설정
 *
 * Spring Boot 3.x에서는 @EnableBatchProcessing 불필요
 * - spring-boot-starter-batch 의존성만으로 자동 설정됨
 * - JobRepository, JobLauncher 등이 자동으로 빈 등록됨
 *
 * Job과 Step은 batch 패키지에서 정의:
 * - batch/job/ApmsAnimalBatchJob.java
 * - batch/reader/ApmsItemReader.java
 * - batch/processor/AnimalItemProcessor.java
 * - batch/writer/AnimalItemWriter.java
 */
@Configuration
public class BatchConfig {
    // Spring Boot 3.x Auto-configuration 사용
    // 커스텀 설정 필요 시 여기에 @Bean 추가
}
