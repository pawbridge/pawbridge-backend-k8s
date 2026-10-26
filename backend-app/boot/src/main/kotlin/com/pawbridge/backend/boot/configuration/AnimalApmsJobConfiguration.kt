package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsAnimalSnapshot
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsCollectionVerification
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsQueryProgress
import com.pawbridge.backend.animal.internal.apms.infrastructure.client.ApmsApiClient
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import com.pawbridge.backend.animal.internal.apms.infrastructure.processor.AnimalItemProcessor
import com.pawbridge.backend.animal.internal.apms.infrastructure.reader.ApmsItemReader
import com.pawbridge.backend.animal.internal.apms.infrastructure.tasklet.ShelterPrepTasklet
import com.pawbridge.backend.animal.internal.apms.infrastructure.writer.AnimalItemWriter
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.JobScope
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.CannotAcquireLockException
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.interceptor.DefaultTransactionAttribute

@Configuration(proxyBeanMethods = false)
internal class AnimalApmsJobConfiguration {
    @Bean
    @JobScope
    fun apmsAnimalSnapshot(
        client: ApmsApiClient,
        settings: ApmsScheduleSettings,
        @Value("#{jobExecution}") execution: JobExecution,
    ): ApmsAnimalSnapshot {
        return ApmsAnimalSnapshot(
            client,
            settings.serviceKey(),
            execution,
            settings.maxRequests(),
            settings.maxAnimals(),
        )
    }

    @Bean
    @StepScope
    fun apmsItemReader(snapshot: ApmsAnimalSnapshot): ApmsItemReader {
        return ApmsItemReader(snapshot)
    }

    @Bean
    @StepScope
    fun apmsAnimalItemProcessor(
        @Qualifier("animalCommandAnimals") animals: AnimalRepository,
        @Qualifier("animalCommandShelters") shelters: ShelterRepository,
    ): AnimalItemProcessor {
        return AnimalItemProcessor(animals, shelters)
    }

    @Bean
    fun apmsAnimalItemWriter(
        @Qualifier("animalCommandSearch") search: SearchDocumentWriter,
        @Qualifier("animalCommandAnimals") animals: AnimalRepository,
    ): AnimalItemWriter {
        return AnimalItemWriter(search, animals)
    }

    @Bean
    fun apmsShelterPrepTasklet(
        @Qualifier("animalCommandSearch") search: SearchDocumentWriter,
        snapshot: ApmsAnimalSnapshot,
        @Qualifier("animalCommandShelters") shelters: ShelterRepository,
        @Qualifier("animalCommandJpaTransactionManager") manager: JpaTransactionManager,
        settings: ApmsScheduleSettings,
    ): ShelterPrepTasklet {
        return ShelterPrepTasklet(search, snapshot, shelters, manager, settings.transactionTimeout())
    }

    @Bean
    fun shelterPrepStep(
        @Qualifier("apmsJobRepository") repository: JobRepository,
        @Qualifier("animalCommandJpaTransactionManager") manager: JpaTransactionManager,
        tasklet: ShelterPrepTasklet,
        settings: ApmsScheduleSettings,
    ): Step {
        val outsideTransaction = timeout(settings)
        outsideTransaction.propagationBehavior = TransactionDefinition.PROPAGATION_NOT_SUPPORTED
        return StepBuilder("shelterPrepStep", repository)
            .tasklet(tasklet, manager)
            .transactionAttribute(outsideTransaction)
            .build()
    }

    @Bean
    fun apmsAnimalSyncStep(
        @Qualifier("apmsJobRepository") repository: JobRepository,
        @Qualifier("animalCommandJpaTransactionManager") manager: JpaTransactionManager,
        reader: ApmsItemReader,
        processor: AnimalItemProcessor,
        writer: AnimalItemWriter,
        @Qualifier("apmsBatchTaskExecutor") executor: AnimalApmsCollectionConfiguration.ApmsChunkExecutor,
        settings: ApmsScheduleSettings,
    ): Step {
        return StepBuilder("apmsAnimalSyncStep", repository)
            .chunk<ApmsAnimal, Animal>(1000, manager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .listener(processor)
            .transactionAttribute(timeout(settings))
            .faultTolerant()
            .retry(CannotAcquireLockException::class.java)
            .retryLimit(3)
            .taskExecutor(executor)
            .throttleLimit(settings.workers())
            .build()
    }

    @Bean
    fun apmsCollectionVerificationStep(
        @Qualifier("apmsJobRepository") repository: JobRepository,
        @Qualifier("animalCommandJpaTransactionManager") manager: JpaTransactionManager,
        settings: ApmsScheduleSettings,
    ): Step {
        return StepBuilder("apmsCollectionVerificationStep", repository)
            .tasklet(ApmsCollectionVerification(), manager)
            .transactionAttribute(timeout(settings))
            .build()
    }

    @Bean
    fun apmsAnimalSyncJob(
        @Qualifier("apmsJobRepository") repository: JobRepository,
        @Qualifier("shelterPrepStep") preparation: Step,
        @Qualifier("apmsAnimalSyncStep") animals: Step,
        @Qualifier("apmsCollectionVerificationStep") verification: Step,
    ): Job {
        val backendRecorder = object : JobExecutionListener {
            override fun beforeJob(execution: JobExecution) {
                execution.executionContext.putString(ApmsQueryProgress.BACKEND_KEY, "postgresql")
            }
        }
        return JobBuilder("apmsAnimalSyncJob", repository)
            .listener(backendRecorder)
            .start(preparation).on("FAILED").fail()
            .from(preparation).on("*").to(animals)
            .from(animals).next(verification).end()
            .build()
    }

    private fun timeout(settings: ApmsScheduleSettings): DefaultTransactionAttribute {
        val attribute = DefaultTransactionAttribute()
        attribute.timeout = settings.transactionTimeout()
        return attribute
    }
}
