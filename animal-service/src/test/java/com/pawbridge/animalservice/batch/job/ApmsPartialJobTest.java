package com.pawbridge.animalservice.batch.job;

import com.pawbridge.animalservice.batch.*;
import com.pawbridge.animalservice.batch.processor.AnimalItemProcessor;
import com.pawbridge.animalservice.batch.reader.ApmsItemReader;
import com.pawbridge.animalservice.batch.tasklet.ShelterPrepTasklet;
import com.pawbridge.animalservice.batch.writer.AnimalItemWriter;
import com.pawbridge.animalservice.client.ApmsApiClient;
import com.pawbridge.animalservice.dto.apms.*;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.repository.AnimalRepository;
import com.pawbridge.animalservice.repository.ShelterRepository;
import com.pawbridge.animalservice.service.ElasticsearchIndexService;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.*;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.scope.JobScope;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApmsPartialJobTest {
    @Test
    void givenIncompleteQuery__whenJobRunsAgainAfterRecovery__thenIndexHealthyDataAndAdvanceCheckpointOnlyOnFullSuccess() throws Exception {
        var api = mock(ApmsApiClient.class);
        var shelters = mock(ShelterRepository.class);
        var processor = mock(AnimalItemProcessor.class);
        var writer = mock(AnimalItemWriter.class);
        var index = mock(ElasticsearchIndexService.class);
        var repository = spy(new ResourcelessJobRepository());
        var outage = new AtomicBoolean(true);
        var persistedDetails = new AtomicReference<String>();
        doAnswer(call -> {
            JobExecution execution = call.getArgument(0);
            if (execution.getExecutionContext().containsKey(ApmsAnimalSnapshot.INCOMPLETE_DETAILS)) {
                persistedDetails.set(execution.getExecutionContext().getString(ApmsAnimalSnapshot.INCOMPLETE_DETAILS));
            }
            return null;
        }).when(repository).updateExecutionContext(any(JobExecution.class));
        var animal = ApmsAnimal.builder().desertionNo("confirmed").happenDt("20260910")
                .updTm("2026-09-10 10:00:00").processState("종료(입양)").build();
        when(api.getAbandonmentAnimals(anyString(), anyInt(), anyInt(), anyString(), anyString(),
                isNull(), isNull(), eq("json"), nullable(String.class), nullable(String.class)))
                .thenAnswer(call -> {
                    if (call.getArgument(8) != null && outage.get()) throw new IllegalStateException("secret-placeholder");
                    return new ApmsRootResponse<>(new ApmsResponse<>(ApmsHeader.builder().resultCode("00").build(),
                            new ApmsBody<>(new ApmsItems<>(List.of(animal)), "1000", "1", "1")));
                });
        when(processor.process(animal)).thenReturn(Animal.builder().build());
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
            context.registerBean("batchTaskExecutor", TaskExecutor.class, SyncTaskExecutor::new);
            context.register(ApmsAnimalSnapshot.class, ApmsItemReader.class, ShelterPrepTasklet.class, ApmsAnimalBatchJob.class);
            context.refresh();
            var day = LocalDate.of(2026, 9, 10);
            var plan = new ApmsSyncPlan(day, day, day, day);
            var job = context.getBean(Job.class);
            var first = repository.createJobExecution(job.getName(), new JobParametersBuilder(plan.parameters()).addLong("run", 1L).toJobParameters());
            job.execute(first);
            assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(persistedDetails.get()).contains("REQUEST_FAILED", "2026-09-10").doesNotContain("secret-placeholder");
            var order = inOrder(writer, index);
            order.verify(writer).write(any());
            order.verify(index).indexAllAnimals();
            assertThat(recoveryPlan(first).updatedStart()).isEqualTo(LocalDate.of(2026, 7, 1));

            outage.set(false);
            var second = repository.createJobExecution(job.getName(), new JobParametersBuilder(plan.parameters()).addLong("run", 2L).toJobParameters());
            job.execute(second);
            assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(persistedDetails.get()).isEmpty();
            assertThat(second.getExecutionContext().getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isZero();
            assertThat(recoveryPlan(second).updatedStart()).isEqualTo(LocalDate.of(2026, 8, 11));
            verify(processor, times(2)).process(animal);
            verify(writer, times(2)).write(any());
            verify(index, times(2)).indexAllAnimals();
        }
    }

    private ApmsSyncPlan recoveryPlan(JobExecution latest) {
        var explorer = mock(JobExplorer.class);
        var oldDay = LocalDate.of(2026, 7, 1);
        var oldPlan = new ApmsSyncPlan(oldDay.minusDays(30), oldDay.minusDays(30), oldDay.minusDays(30), oldDay);
        var old = new JobExecution(new JobInstance(900L, "apmsAnimalSyncJob"), 900L, oldPlan.parameters());
        old.setStatus(BatchStatus.COMPLETED);
        when(explorer.getJobInstances("apmsAnimalSyncJob", 0, 100)).thenReturn(List.of(latest.getJobInstance(), old.getJobInstance()));
        when(explorer.getJobExecutions(latest.getJobInstance())).thenReturn(List.of(latest));
        lenient().when(explorer.getJobExecutions(old.getJobInstance())).thenReturn(List.of(old));
        return new ApmsSyncPlanFactory(mock(AnimalRepository.class), explorer,
                Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneId.of("Asia/Seoul"))).create("apmsAnimalSyncJob");
    }
}
