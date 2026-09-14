package com.pawbridge.animalservice.travel;

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
public class PetTravelBatchConfiguration {
    @Bean
    public Job petTravelCollectionJob(JobRepository repository, PlatformTransactionManager manager, PetTravelCollector collector) {
        var noTransaction=new DefaultTransactionAttribute();
        noTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var step=new StepBuilder("petTravelCollectionStep",repository)
                .tasklet((contribution,context)-> {
                    var result=collector.collect();
                    contribution.setExitStatus(new org.springframework.batch.core.ExitStatus(result.status()));
                    return RepeatStatus.FINISHED;
                },manager).transactionAttribute(noTransaction).build();
        return new JobBuilder("petTravelCollectionJob",repository).start(step).build();
    }
}
