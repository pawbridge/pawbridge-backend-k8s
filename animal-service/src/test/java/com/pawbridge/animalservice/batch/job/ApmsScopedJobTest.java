package com.pawbridge.animalservice.batch.job;

import com.pawbridge.animalservice.batch.ApmsAnimalSnapshot;
import com.pawbridge.animalservice.batch.ApmsSyncPlan;
import com.pawbridge.animalservice.batch.processor.AnimalItemProcessor;
import com.pawbridge.animalservice.batch.reader.ApmsItemReader;
import com.pawbridge.animalservice.batch.tasklet.ShelterPrepTasklet;
import com.pawbridge.animalservice.batch.writer.AnimalItemWriter;
import com.pawbridge.animalservice.client.ApmsApiClient;
import com.pawbridge.animalservice.dto.apms.*;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.repository.ShelterRepository;
import com.pawbridge.animalservice.service.ElasticsearchIndexService;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.scope.JobScope;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApmsScopedJobTest {
    @Test
    void givenTwoExecutions__whenRealStepsRunOnWorkerThread__thenShareOneSnapshotPerExecution() throws Exception {
        var api = mock(ApmsApiClient.class);
        var shelters = mock(ShelterRepository.class);
        var processor = mock(AnimalItemProcessor.class);
        var writer = mock(AnimalItemWriter.class);
        var index = mock(ElasticsearchIndexService.class);
        var repository = new ResourcelessJobRepository();
        var executor = Executors.newSingleThreadExecutor();
        long callerThread = Thread.currentThread().getId();
        var first = ApmsAnimal.builder().desertionNo("first").happenDt("20260908").build();
        var second = ApmsAnimal.builder().desertionNo("second").happenDt("20260909").build();
        when(api.getAbandonmentAnimals(anyString(), anyInt(), anyInt(), anyString(), anyString(),
                isNull(), isNull(), eq("json"), nullable(String.class), nullable(String.class)))
                .thenAnswer(call -> page(List.of()));
        when(api.getAbandonmentAnimals("test-key", 1, 1000, "20260811", "20260910", null, null, "json", null, null))
                .thenReturn(page(List.of(first)), page(List.of(second)));
        when(processor.process(any())).thenAnswer(call -> {
            assertThat(Thread.currentThread().getId()).isNotEqualTo(callerThread);
            return Animal.builder().build();
        });
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of("apms.api.service-key", "test-key")));
            context.getBeanFactory().registerScope("job", new JobScope());
            context.registerBean(JobRepository.class, () -> repository);
            context.registerBean(PlatformTransactionManager.class, ResourcelessTransactionManager::new);
            context.registerBean(ApmsApiClient.class, () -> api);
            context.registerBean(ShelterRepository.class, () -> shelters);
            context.registerBean(AnimalItemProcessor.class, () -> processor);
            context.registerBean(AnimalItemWriter.class, () -> writer);
            context.registerBean(ElasticsearchIndexService.class, () -> index);
            context.registerBean("batchTaskExecutor", TaskExecutor.class, () -> executor::execute);
            context.register(ApmsAnimalSnapshot.class, ApmsItemReader.class, ShelterPrepTasklet.class, ApmsAnimalBatchJob.class);
            context.refresh();
            var plan = new ApmsSyncPlan(LocalDate.of(2026, 8, 11), LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 8, 11), LocalDate.of(2026, 9, 10));
            for (long run = 1; run <= 2; run++) {
                var params = new JobParametersBuilder(plan.parameters()).addLong("run", run).toJobParameters();
                var execution = repository.createJobExecution("apmsAnimalSyncJob", params);
                context.getBean(Job.class).execute(execution);
                assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            }
            verify(processor).process(first);
            verify(processor).process(second);
            verify(index, times(2)).indexAllAnimals();
            // Four queries per execution; shelter preparation and reader do not fetch independently.
            verify(api, times(8)).getAbandonmentAnimals(anyString(), anyInt(), anyInt(), anyString(), anyString(),
                    isNull(), isNull(), eq("json"), nullable(String.class), nullable(String.class));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ApmsRootResponse<ApmsAnimal> page(List<ApmsAnimal> animals) {
        return new ApmsRootResponse<>(new ApmsResponse<>(ApmsHeader.builder().resultCode("00").build(),
                new ApmsBody<>(new ApmsItems<>(animals), "1000", "1", String.valueOf(animals.size()))));
    }
}
