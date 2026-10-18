package com.pawbridge.animalservice.shelter;

import java.util.UUID;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Disabled until operator configuration/usage conditions are approved. No public trigger endpoint. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="shelter-directory",name="schedule-enabled",havingValue="true")
public class ShelterDirectorySchedule {
    private final TaskExecutorJobLauncher launcher;
    private final Job job;
    public ShelterDirectorySchedule(JobRepository repository,@Qualifier("shelterDirectoryJob") Job job) throws Exception {
        this.job=job;
        launcher=new TaskExecutorJobLauncher();
        launcher.setJobRepository(repository);launcher.setTaskExecutor(new SyncTaskExecutor());launcher.afterPropertiesSet();
    }
    @Scheduled(cron="${shelter-directory.collection-cron:0 0 4 * * *}",zone="Asia/Seoul")
    public void collect() throws Exception {
        launcher.run(job,new JobParametersBuilder().addString("requestId",UUID.randomUUID().toString()).toJobParameters());
    }
}
