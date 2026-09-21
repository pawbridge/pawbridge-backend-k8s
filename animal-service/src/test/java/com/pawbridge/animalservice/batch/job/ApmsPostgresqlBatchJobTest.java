package com.pawbridge.animalservice.batch.job;

import com.pawbridge.animalservice.batch.ApmsAnimalSnapshot;
import com.pawbridge.animalservice.batch.ApmsSyncPlan;
import com.pawbridge.animalservice.batch.ApmsQueryProgress;
import java.time.LocalDate;
import java.util.stream.IntStream;
import com.pawbridge.animalservice.batch.processor.AnimalItemProcessor;
import com.pawbridge.animalservice.batch.reader.ApmsItemReader;
import com.pawbridge.animalservice.batch.tasklet.ShelterPrepTasklet;
import com.pawbridge.animalservice.batch.writer.AnimalItemWriter;
import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import com.pawbridge.animalservice.entity.Animal;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApmsPostgresqlBatchJobTest {
    @ParameterizedTest
    @ValueSource(strings={"complete","write-failure","incomplete"})
    void postgresql_job_requires_no_es_but_preserves_write_and_collection_failure(String scenario) throws Exception {
        ApmsItemReader reader=mock(ApmsItemReader.class);
        AnimalItemProcessor processor=mock(AnimalItemProcessor.class);
        AnimalItemWriter writer=mock(AnimalItemWriter.class);
        ShelterPrepTasklet preparation=mock(ShelterPrepTasklet.class);
        ResourcelessJobRepository repository=new ResourcelessJobRepository();
        when(preparation.execute(any(),any())).thenReturn(RepeatStatus.FINISHED);
        ApmsAnimal item=new ApmsAnimal();
        when(reader.read()).thenReturn(item).thenReturn(null);
        when(processor.process(item)).thenReturn(Animal.builder().build());
        if (scenario.equals("write-failure")) doThrow(new IllegalStateException("write failed")).when(writer).write(any());
        try (AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of("pawbridge.animal-query.backend","postgresql")));
            context.registerBean(JobRepository.class,()->repository);
            context.registerBean(PlatformTransactionManager.class,ResourcelessTransactionManager::new);
            context.registerBean(ApmsItemReader.class,()->reader);
            context.registerBean(AnimalItemProcessor.class,()->processor);
            context.registerBean(AnimalItemWriter.class,()->writer);
            context.registerBean(ShelterPrepTasklet.class,()->preparation);
            context.registerBean("batchTaskExecutor",TaskExecutor.class,SyncTaskExecutor::new);
            context.register(ApmsAnimalBatchJob.class);
            context.refresh();
            Job job=context.getBean("apmsAnimalSyncJob",Job.class);
            LocalDate day=LocalDate.of(2026,9,20);
            ApmsSyncPlan plan=new ApmsSyncPlan(day,day,day,day);
            JobExecution execution=repository.createJobExecution(job.getName(),plan.parameters());
            execution.getExecutionContext().putInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT,0);
            ApmsQueryProgress.store(execution,IntStream.range(0,plan.queries().size())
                    .mapToObj(i->new ApmsQueryProgress.Result(plan.queries().get(i),!scenario.equals("incomplete") || i!=0)).toList());
            if (scenario.equals("incomplete")) execution.getExecutionContext().putInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT,1);
            job.execute(execution);
            assertThat(execution.getStatus()).isEqualTo(scenario.equals("complete")?BatchStatus.COMPLETED:BatchStatus.FAILED);
            assertThat(execution.getStepExecutions()).noneMatch(step->step.getStepName().equals("elasticsearchIndexStep"));
            assertThat(context.containsBean("elasticsearchIndexStep")).isFalse();
            verify(writer).write(any());
            if (scenario.equals("write-failure")) assertThat(ApmsQueryProgress.verifiedResults(execution,plan)).isEmpty();
            else assertThat(ApmsQueryProgress.verifiedResults(execution,plan)).hasSize(plan.queries().size());
            if (scenario.equals("write-failure")) assertThat(execution.getStepExecutions())
                    .noneMatch(step->step.getStepName().equals("apmsCollectionVerificationStep"));
        }
    }
}
