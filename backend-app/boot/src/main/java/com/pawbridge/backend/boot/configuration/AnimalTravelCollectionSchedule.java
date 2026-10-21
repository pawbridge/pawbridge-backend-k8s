package com.pawbridge.backend.boot.configuration;

import java.util.UUID;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** A single explicit owner, with normal shutdown waiting for its current bounded collection. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "tourapi", name = "schedule-enabled", havingValue = "true")
class AnimalTravelCollectionSchedule {
    private final TaskExecutorJobLauncher launcher;
    private final Job job;

    AnimalTravelCollectionSchedule(@Qualifier("travelCollectionJobRepository") JobRepository repository,
            @Qualifier("petTravelCollectionJob") Job job) throws Exception {
        this.job = job;
        launcher = new TaskExecutorJobLauncher(); launcher.setJobRepository(repository);
        launcher.setTaskExecutor(new SyncTaskExecutor()); launcher.afterPropertiesSet();
    }

    @Bean(name = "tourApiCollectionScheduler", destroyMethod = "shutdown")
    @DependsOn({"writeDataSource", "petTravelCollectionJob"})
    static ThreadPoolTaskScheduler tourApiCollectionScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("pawbridge-tourapi-");
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(Integer.MAX_VALUE);
        return scheduler;
    }

    @Scheduled(cron = "${tourapi.collection-cron:0 */30 * * * *}", zone = "Asia/Seoul", scheduler = "tourApiCollectionScheduler")
    void collect() throws Exception {
        launcher.run(job, new JobParametersBuilder().addString("requestId", UUID.randomUUID().toString()).toJobParameters());
    }
}
