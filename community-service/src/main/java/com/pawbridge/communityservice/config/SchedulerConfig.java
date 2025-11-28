package com.pawbridge.communityservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduler 설정
 *
 * @EnableScheduling:
 * - @Scheduled 어노테이션 활성화
 * - CleanupScheduler, SyncScheduler의 배치 작업 실행
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {
}
