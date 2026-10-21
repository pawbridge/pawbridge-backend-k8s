package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsAnimalSnapshot;
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsCollectionVerification;
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsQueryProgress;
import com.pawbridge.backend.animal.internal.apms.infrastructure.client.ApmsApiClient;
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal;
import com.pawbridge.backend.animal.internal.apms.infrastructure.processor.AnimalItemProcessor;
import com.pawbridge.backend.animal.internal.apms.infrastructure.reader.ApmsItemReader;
import com.pawbridge.backend.animal.internal.apms.infrastructure.tasklet.ShelterPrepTasklet;
import com.pawbridge.backend.animal.internal.apms.infrastructure.writer.AnimalItemWriter;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

@Configuration(proxyBeanMethods = false)
class AnimalApmsJobConfiguration {
    @Bean
    @JobScope
    ApmsAnimalSnapshot apmsAnimalSnapshot(ApmsApiClient client, ApmsScheduleSettings settings,
            @Value("#{jobExecution}") JobExecution execution) {
        return new ApmsAnimalSnapshot(client, settings.serviceKey(), execution, settings.maxRequests(), settings.maxAnimals());
    }

    @Bean
    @StepScope
    ApmsItemReader apmsItemReader(ApmsAnimalSnapshot snapshot) { return new ApmsItemReader(snapshot); }

    @Bean
    @StepScope
    AnimalItemProcessor apmsAnimalItemProcessor(@Qualifier("animalCommandAnimals") AnimalRepository animals,
            @Qualifier("animalCommandShelters") ShelterRepository shelters) {
        return new AnimalItemProcessor(animals, shelters);
    }

    @Bean
    AnimalItemWriter apmsAnimalItemWriter(@Qualifier("animalCommandSearch") SearchDocumentWriter search,
            @Qualifier("animalCommandAnimals") AnimalRepository animals) {
        return new AnimalItemWriter(search, animals);
    }

    @Bean
    ShelterPrepTasklet apmsShelterPrepTasklet(@Qualifier("animalCommandSearch") SearchDocumentWriter search,
            ApmsAnimalSnapshot snapshot, @Qualifier("animalCommandShelters") ShelterRepository shelters,
            @Qualifier("animalCommandJpaTransactionManager") JpaTransactionManager manager, ApmsScheduleSettings settings) {
        return new ShelterPrepTasklet(search, snapshot, shelters, manager, settings.transactionTimeout());
    }

    @Bean
    Step shelterPrepStep(@Qualifier("apmsJobRepository") JobRepository repository,
            @Qualifier("animalCommandJpaTransactionManager") JpaTransactionManager manager,
            ShelterPrepTasklet tasklet, ApmsScheduleSettings settings) {
        var outsideTransaction = timeout(settings);
        outsideTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return new StepBuilder("shelterPrepStep", repository).tasklet(tasklet, manager)
                .transactionAttribute(outsideTransaction).build();
    }

    @Bean
    Step apmsAnimalSyncStep(@Qualifier("apmsJobRepository") JobRepository repository,
            @Qualifier("animalCommandJpaTransactionManager") JpaTransactionManager manager,
            ApmsItemReader reader, AnimalItemProcessor processor, AnimalItemWriter writer,
            @Qualifier("apmsBatchTaskExecutor") AnimalApmsCollectionConfiguration.ApmsChunkExecutor executor,
            ApmsScheduleSettings settings) {
        return new StepBuilder("apmsAnimalSyncStep", repository).<ApmsAnimal, Animal>chunk(1000, manager)
                .reader(reader).processor(processor).writer(writer).listener(processor)
                .transactionAttribute(timeout(settings))
                .faultTolerant().retry(CannotAcquireLockException.class).retryLimit(3)
                .taskExecutor(executor).throttleLimit(settings.workers()).build();
    }

    @Bean
    Step apmsCollectionVerificationStep(@Qualifier("apmsJobRepository") JobRepository repository,
            @Qualifier("animalCommandJpaTransactionManager") JpaTransactionManager manager, ApmsScheduleSettings settings) {
        return new StepBuilder("apmsCollectionVerificationStep", repository)
                .tasklet(new ApmsCollectionVerification(), manager).transactionAttribute(timeout(settings)).build();
    }

    @Bean
    Job apmsAnimalSyncJob(@Qualifier("apmsJobRepository") JobRepository repository,
            @Qualifier("shelterPrepStep") Step preparation, @Qualifier("apmsAnimalSyncStep") Step animals,
            @Qualifier("apmsCollectionVerificationStep") Step verification) {
        return new JobBuilder("apmsAnimalSyncJob", repository).listener(new JobExecutionListener() {
            @Override public void beforeJob(JobExecution execution) {
                execution.getExecutionContext().putString(ApmsQueryProgress.BACKEND_KEY, "postgresql");
            }
        }).start(preparation).on("FAILED").fail()
                .from(preparation).on("*").to(animals)
                .from(animals).next(verification).end().build();
    }

    private static DefaultTransactionAttribute timeout(ApmsScheduleSettings settings) {
        var attribute = new DefaultTransactionAttribute();
        attribute.setTimeout(settings.transactionTimeout());
        return attribute;
    }
}
