package com.pawbridge.animalservice.batch.job;

import com.pawbridge.animalservice.batch.processor.AnimalItemProcessor;
import com.pawbridge.animalservice.batch.reader.ApmsItemReader;
import com.pawbridge.animalservice.batch.tasklet.ShelterPrepTasklet;
import com.pawbridge.animalservice.batch.writer.AnimalItemWriter;
import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.service.ElasticsearchIndexService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApmsAnimalBatchJobTest {
    @Mock private ApmsItemReader reader;
    @Mock private AnimalItemProcessor processor;
    @Mock private AnimalItemWriter writer;
    @Mock private ElasticsearchIndexService indexService;
    @Mock private ShelterPrepTasklet shelterPrep;
    private ResourcelessJobRepository repository;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        repository = new ResourcelessJobRepository();
        // Preserve the production @Configuration singleton Step identities. No Boot or external services.
        context = new AnnotationConfigApplicationContext();
        context.registerBean(JobRepository.class, () -> repository);
        context.registerBean(PlatformTransactionManager.class, ResourcelessTransactionManager::new);
        context.registerBean(ApmsItemReader.class, () -> reader);
        context.registerBean(AnimalItemProcessor.class, () -> processor);
        context.registerBean(AnimalItemWriter.class, () -> writer);
        context.registerBean(ElasticsearchIndexService.class, () -> indexService);
        context.registerBean(ShelterPrepTasklet.class, () -> shelterPrep);
        context.registerBean("batchTaskExecutor", TaskExecutor.class, SyncTaskExecutor::new);
        context.register(ApmsAnimalBatchJob.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void givenShelterPreparationFailure__whenJobRuns__thenDoNotIngestOrIndex() throws Exception {
        when(shelterPrep.execute(any(), any())).thenThrow(new IllegalStateException("provider error"));
        assertThat(runJob().getStatus()).isEqualTo(BatchStatus.FAILED);
        verifyNoInteractions(reader, writer, indexService);
    }

    @Test
    void givenReadFailure__whenJobRuns__thenFailWithoutSkippingOrIndexing() throws Exception {
        when(shelterPrep.execute(any(), any())).thenReturn(RepeatStatus.FINISHED);
        when(reader.read()).thenThrow(new IllegalStateException("provider error"));
        assertFailedWithoutSkips(runJob());
        verifyNoInteractions(writer, indexService);
    }

    @Test
    void givenProcessingFailure__whenJobRuns__thenFailWithoutSkippingOrIndexing() throws Exception {
        when(shelterPrep.execute(any(), any())).thenReturn(RepeatStatus.FINISHED);
        var item = new ApmsAnimal();
        when(reader.read()).thenReturn(item).thenReturn(null);
        when(processor.process(item)).thenThrow(new IllegalArgumentException("invalid animal"));
        assertFailedWithoutSkips(runJob());
        verifyNoInteractions(writer, indexService);
    }

    @Test
    void givenWriteFailure__whenJobRuns__thenFailWithoutSkippingOrIndexing() throws Exception {
        when(shelterPrep.execute(any(), any())).thenReturn(RepeatStatus.FINISHED);
        var item = new ApmsAnimal();
        when(reader.read()).thenReturn(item).thenReturn(null);
        when(processor.process(item)).thenReturn(Animal.builder().build());
        doThrow(new IllegalStateException("database write failure")).when(writer).write(any());
        assertFailedWithoutSkips(runJob());
        verify(writer, times(1)).write(any());
        verifyNoInteractions(indexService);
    }

    @Test
    void givenSuccessfulIngestion__whenJobRuns__thenIndexAfterWriting() throws Exception {
        when(shelterPrep.execute(any(), any())).thenReturn(RepeatStatus.FINISHED);
        var item = new ApmsAnimal();
        when(reader.read()).thenReturn(item).thenReturn(null);
        when(processor.process(item)).thenReturn(Animal.builder().build());
        JobExecution execution = runJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        var order = inOrder(writer, indexService);
        order.verify(writer).write(any());
        order.verify(indexService).indexAllAnimals();
        verify(reader).beforeStep(any());
    }

    @Test
    void givenElasticsearchFailure__whenJobRuns__thenDoNotReportSuccessfulCollection() throws Exception {
        when(shelterPrep.execute(any(), any())).thenReturn(RepeatStatus.FINISHED);
        when(reader.read()).thenReturn(null);
        when(indexService.indexAllAnimals()).thenThrow(new IllegalStateException("search update failed"));
        assertThat(runJob().getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    private JobExecution runJob() {
        var execution = repository.createJobExecution("apmsAnimalSyncJob", new JobParameters());
        context.getBean(Job.class).execute(execution);
        return execution;
    }

    private void assertFailedWithoutSkips(JobExecution execution) {
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getStepExecutions()).allSatisfy(step -> assertThat(step.getSkipCount()).isZero());
    }
}
