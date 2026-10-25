package com.pawbridge.backend.boot.configuration

import java.util.UUID
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher
import org.springframework.batch.core.repository.JobRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/** Disabled until operator configuration/usage conditions are approved. No public trigger endpoint. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
internal class ShelterDirectorySchedule @Throws(Exception::class) constructor(
    @Qualifier("shelterDirectoryJobRepository") repository: JobRepository,
    @Qualifier("shelterDirectoryJob") private val job: Job,
) {
    private val launcher = TaskExecutorJobLauncher()

    init {
        launcher.setJobRepository(repository)
        launcher.setTaskExecutor(SyncTaskExecutor())
        launcher.afterPropertiesSet()
    }

    @Scheduled(
        cron = "\${shelter-directory.collection-cron:0 0 4 * * *}",
        zone = "Asia/Seoul",
        scheduler = "shelterDirectoryScheduler",
    )
    @Throws(Exception::class)
    fun collect() {
        launcher.run(job, JobParametersBuilder().addString("requestId", UUID.randomUUID().toString()).toJobParameters())
    }

    companion object {
        @Bean(name = ["shelterDirectoryScheduler"], destroyMethod = "shutdown")
        @DependsOn("writeDataSource", "shelterDirectoryJob")
        @JvmStatic
        fun shelterDirectoryScheduler(): ThreadPoolTaskScheduler {
            val scheduler = ThreadPoolTaskScheduler()
            scheduler.poolSize = 1
            scheduler.setThreadNamePrefix("pawbridge-shelter-directory-")
            scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
            scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
            scheduler.setRemoveOnCancelPolicy(true)
            scheduler.setWaitForTasksToCompleteOnShutdown(true)
            scheduler.setAwaitTerminationSeconds(Int.MAX_VALUE)
            return scheduler
        }
    }
}
