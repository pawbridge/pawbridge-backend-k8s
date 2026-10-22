package com.pawbridge.backend.boot.configuration;

import java.util.UUID;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Disabled until operator configuration/usage conditions are approved. No public trigger endpoint. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ShelterDirectorySchedule {
    private final TaskExecutorJobLauncher launcher;
    private final Job job;

    ShelterDirectorySchedule(@Qualifier("shelterDirectoryJobRepository") JobRepository repository,
            @Qualifier("shelterDirectoryJob") Job job) throws Exception {
        this.job = job;
        launcher = new TaskExecutorJobLauncher(); launcher.setJobRepository(repository);
        launcher.setTaskExecutor(new SyncTaskExecutor()); launcher.afterPropertiesSet();
    }

    @Bean(name = "shelterDirectoryScheduler", destroyMethod = "shutdown")
    @DependsOn({"writeDataSource", "shelterDirectoryJob"})
    static ThreadPoolTaskScheduler shelterDirectoryScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("pawbridge-shelter-directory-");
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(Integer.MAX_VALUE);
        return scheduler;
    }

    @Scheduled(cron = "${shelter-directory.collection-cron:0 0 4 * * *}", zone = "Asia/Seoul", scheduler = "shelterDirectoryScheduler")
    void collect() throws Exception {
        launcher.run(job, new JobParametersBuilder().addString("requestId", UUID.randomUUID().toString()).toJobParameters());
    }
}
