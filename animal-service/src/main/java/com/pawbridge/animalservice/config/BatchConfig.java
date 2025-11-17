package com.pawbridge.animalservice.config;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Batch 설정
 */
@Configuration
@EnableBatchProcessing
public class BatchConfig {
    // Job과 Step은 별도 클래스에서 정의
}
