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
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.core.explore.support.JobExplorerFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.batch.core.scope.JobScope;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@JdbcTest
@ActiveProfiles("ci")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ApmsPartialJobTest.JdbcConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApmsPartialJobTest {
    @Autowired private DataSource dataSource;

    @Configuration(proxyBeanMethods = false)
    static class JdbcConfiguration { }

    @Test
    void givenIncompleteQuery__whenJobRunsAgainAfterRecovery__thenIndexHealthyDataAndAdvanceCheckpointOnlyOnFullSuccess() throws Exception {
        var api = mock(ApmsApiClient.class);
        var shelters = mock(ShelterRepository.class);
        var processor = mock(AnimalItemProcessor.class);
        var writer = mock(AnimalItemWriter.class);
        var index = mock(ElasticsearchIndexService.class);
        String prefix = "QP_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "_";
        schema("schema-mysql.sql", prefix);
        var transactionManager = new DataSourceTransactionManager(dataSource);
        var repositoryFactory = new JobRepositoryFactoryBean();
        repositoryFactory.setDataSource(dataSource);
        repositoryFactory.setTransactionManager(transactionManager);
        repositoryFactory.setTablePrefix(prefix);
        repositoryFactory.afterPropertiesSet();
        var repository = repositoryFactory.getObject();
        var explorerFactory = new JobExplorerFactoryBean();
        explorerFactory.setDataSource(dataSource);
        explorerFactory.setTransactionManager(transactionManager);
        explorerFactory.setTablePrefix(prefix);
        explorerFactory.afterPropertiesSet();
        var jdbcExplorer = explorerFactory.getObject();
        var outage = new AtomicBoolean(true);
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
            context.registerBean(PlatformTransactionManager.class, () -> transactionManager);
            context.registerBean(ApmsApiClient.class, () -> api);
            context.registerBean(ShelterRepository.class, () -> shelters);
            context.registerBean(AnimalItemProcessor.class, () -> processor);
            context.registerBean(AnimalItemWriter.class, () -> writer);
            context.registerBean(ElasticsearchIndexService.class, () -> index);
            context.registerBean("batchTaskExecutor", TaskExecutor.class, SyncTaskExecutor::new);
            context.register(ApmsAnimalSnapshot.class, ApmsItemReader.class, ShelterPrepTasklet.class, ApmsAnimalBatchJob.class);
            context.refresh();
            var day = LocalDate.of(2026, 9, 10);
            var plan = new ApmsSyncPlan(day, day, day, day, Map.of(day.withDayOfMonth(1), day));
            var job = context.getBean(Job.class);
            var first = repository.createJobExecution(job.getName(), new JobParametersBuilder(plan.parameters()).addLong("run", 1L).toJobParameters());
            job.execute(first);
            assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
            var restoredFirst = jdbcExplorer.getJobExecutions(first.getJobInstance()).get(0);
            assertThat(restoredFirst).isNotSameAs(first);
            assertThat(ApmsSyncPlan.from(restoredFirst.getJobParameters())).isEqualTo(plan);
            assertThat(restoredFirst.getExecutionContext().getString(ApmsAnimalSnapshot.INCOMPLETE_DETAILS)).contains("REQUEST_FAILED", "2026-09-10").doesNotContain("secret-placeholder");
            var order = inOrder(writer, index);
            order.verify(writer).write(any());
            order.verify(index).indexAllAnimals();
            assertThat(ApmsQueryProgress.verifiedResults(restoredFirst, plan)).extracting(ApmsQueryProgress.Result::complete).containsExactly(true, false);
            assertThat(recoveryPlan(restoredFirst).updatedStart()).isEqualTo(LocalDate.of(2026, 7, 1));

            outage.set(false);
            var second = repository.createJobExecution(job.getName(), new JobParametersBuilder(plan.parameters()).addLong("run", 2L).toJobParameters());
            job.execute(second);
            assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            var restoredSecond = jdbcExplorer.getJobExecutions(second.getJobInstance()).get(0);
            assertThat(restoredSecond.getExecutionContext().getString(ApmsAnimalSnapshot.INCOMPLETE_DETAILS)).isEmpty();
            assertThat(ApmsQueryProgress.verifiedResults(restoredSecond, plan)).extracting(ApmsQueryProgress.Result::complete).containsExactly(true, true);
            assertThat(second.getExecutionContext().getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isZero();
            assertThat(recoveryPlan(restoredSecond).updatedStart()).isEqualTo(LocalDate.of(2026, 8, 11));
            verify(processor, times(2)).process(animal);
            verify(writer, times(2)).write(any());
            verify(index, times(2)).indexAllAnimals();
        } finally {
            schema("schema-drop-mysql.sql", prefix);
        }
    }

    private void schema(String resource, String prefix) throws Exception {
        String script = new ClassPathResource("org/springframework/batch/core/" + resource)
                .getContentAsString(StandardCharsets.UTF_8).replace("BATCH_", prefix)
                .replaceAll("(?i)constraint ([A-Z_]+)", "constraint " + prefix + "$1");
        new ResourceDatabasePopulator(new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8))).execute(dataSource);
    }

    private ApmsSyncPlan recoveryPlan(JobExecution latest) {
        var explorer = mock(JobExplorer.class);
        var oldDay = LocalDate.of(2026, 7, 1);
        var oldPlan = new ApmsSyncPlan(oldDay.minusDays(30), oldDay.minusDays(30), oldDay.minusDays(30), oldDay);
        var old = new JobExecution(new JobInstance(900L, "apmsAnimalSyncJob"), 900L, new JobParametersBuilder(oldPlan.parameters()).addString(ApmsSyncPlan.CONTRACT_KEY, ApmsSyncPlan.LEGACY_CONTRACT).toJobParameters());
        old.setStatus(BatchStatus.COMPLETED);
        when(explorer.getJobInstances("apmsAnimalSyncJob", 0, 100)).thenReturn(List.of(latest.getJobInstance(), old.getJobInstance()));
        when(explorer.getJobExecutions(latest.getJobInstance())).thenReturn(List.of(latest));
        lenient().when(explorer.getJobExecutions(old.getJobInstance())).thenReturn(List.of(old));
        return new ApmsSyncPlanFactory(mock(AnimalRepository.class), explorer,
                Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneId.of("Asia/Seoul"))).create("apmsAnimalSyncJob");
    }
}
