package com.pawbridge.animalservice.shelter;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

@Configuration
public class ShelterDirectoryBatchConfiguration {
    @Bean
    public Job shelterDirectoryJob(JobRepository repository, PlatformTransactionManager manager, ShelterDirectoryCollector collector) {
        var noTransaction=new DefaultTransactionAttribute();
        noTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var step=new StepBuilder("shelterDirectoryStep",repository)
                .tasklet((contribution,context)-> {
                    var result=collector.collect();
                    contribution.incrementWriteCount(result);
                    return RepeatStatus.FINISHED;
                },manager).transactionAttribute(noTransaction).build();
        return new JobBuilder("shelterDirectoryJob",repository).start(step).build();
    }
}
