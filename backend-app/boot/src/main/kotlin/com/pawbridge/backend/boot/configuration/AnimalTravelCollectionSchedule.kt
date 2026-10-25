package com.pawbridge.backend.boot.configuration

import java.util.UUID
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher
import org.springframework.batch.core.repository.JobRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/** A single explicit owner, with normal shutdown waiting for its current bounded collection. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "tourapi", name = ["schedule-enabled"], havingValue = "true")
internal class AnimalTravelCollectionSchedule @Throws(Exception::class) constructor(
    @Qualifier("travelCollectionJobRepository") repository: JobRepository,
    @Qualifier("petTravelCollectionJob") private val job: Job,
) {
    private val launcher = TaskExecutorJobLauncher()

    init {
        launcher.setJobRepository(repository)
        launcher.setTaskExecutor(SyncTaskExecutor())
        launcher.afterPropertiesSet()
    }

    @Scheduled(cron = "\${tourapi.collection-cron:0 */30 * * * *}", zone = "Asia/Seoul", scheduler = "tourApiCollectionScheduler")
    @Throws(Exception::class)
    fun collect() {
        launcher.run(job, JobParametersBuilder().addString("requestId", UUID.randomUUID().toString()).toJobParameters())
    }

    companion object {
        @Bean(name = ["tourApiCollectionScheduler"], destroyMethod = "shutdown")
        @DependsOn("writeDataSource", "petTravelCollectionJob")
        @JvmStatic
        fun tourApiCollectionScheduler(): ThreadPoolTaskScheduler {
            val scheduler = ThreadPoolTaskScheduler()
            scheduler.poolSize = 1
            scheduler.setThreadNamePrefix("pawbridge-tourapi-")
            scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
            scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
            scheduler.setRemoveOnCancelPolicy(true)
            scheduler.setWaitForTasksToCompleteOnShutdown(true)
            scheduler.setAwaitTerminationSeconds(Int.MAX_VALUE)
            return scheduler
        }
    }
}
