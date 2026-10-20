package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.boot.configuration.VisitCollectionConfiguration;
import com.pawbridge.backend.community.internal.visit.application.*;
import com.pawbridge.backend.community.internal.visit.web.VisitCollectionController;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ErrorHandler;

@ExtendWith(OutputCaptureExtension.class)
class VisitCollectionConfigurationTest {
    @ParameterizedTest @ValueSource(strings={"absent","false"})
    void given__read_enabled_without_collection_or_key__when__assembling__then__no_collection_or_scheduler(String flag) {
        var runner = new ApplicationContextRunner().withUserConfiguration(VisitCollectionConfiguration.class)
                .withPropertyValues("pawbridge.visits.enabled=true");
        if (!flag.equals("absent")) runner=runner.withPropertyValues("pawbridge.visits.collection-enabled="+flag);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(VisitCollection.class)).isEmpty();
            assertThat(context.getBeansOfType(VisitIdentity.class)).isEmpty();
            assertThat(context.getBeansOfType(VisitCollectionController.class)).isEmpty();
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        });
    }
    @Test
    void given__collection_enabled_but_read_disabled_or_key_missing__when__assembling__then__fail_closed() {
        new ApplicationContextRunner().withUserConfiguration(VisitCollectionConfiguration.class)
                .withPropertyValues("pawbridge.visits.collection-enabled=true")
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseMessage("Visit collection requires pawbridge.visits.enabled=true."));
        inputs(false).run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseMessage("Visit signing key must contain at least 32 bytes."));
    }
    @Test
    void given__enabled_with_existing_writer_and_synthetic_key__when__assembling__then__one_qualified_lifecycle_and_original_transaction(CapturedOutput output) throws Exception {
        inputs(true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(VisitCollection.class)).hasSize(1);
            assertThat(context.getBeansOfType(VisitCollectionConfiguration.VisitCollectionLifecycle.class)).hasSize(1);
            var service=context.getBean(VisitCollection.class);
            var store=context.getBean(VisitCollectionStore.class);
            assertThat(ReflectionTestUtils.getField(store,"jdbc")).isSameAs(context.getBean("writeJdbc"));
            assertThat(ReflectionTestUtils.getField(service,"clock")).isSameAs(context.getBean("readClock"));
            var tx=(TransactionTemplate)ReflectionTestUtils.getField(service,"transactions");
            assertThat(tx.getTransactionManager()).isSameAs(context.getBean("writeTransactionManager"));
            assertThat(tx.getTimeout()).isEqualTo(10); assertThat(tx.isReadOnly()).isFalse();
            var scheduler=context.getBean("visitCollectionScheduler",ThreadPoolTaskScheduler.class);
            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).hasSize(1);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy()).isTrue();
            assertThat(scheduler.getScheduledThreadPoolExecutor().getContinueExistingPeriodicTasksAfterShutdownPolicy()).isFalse();
            assertThat(scheduler.getScheduledThreadPoolExecutor().getExecuteExistingDelayedTasksAfterShutdownPolicy()).isFalse();
            assertThat(ReflectionTestUtils.getField(scheduler,"acceptTasksAfterContextClose")).isEqualTo(false);
            assertThat(ReflectionTestUtils.getField(scheduler,"waitForTasksToCompleteOnShutdown")).isEqualTo(false);
            assertThat(ReflectionTestUtils.getField(scheduler,"awaitTerminationMillis")).isEqualTo(5000L);
            assertThat(context.getBeanFactory().getDependenciesForBean("visitCollectionScheduler")).contains("writeDataSource");
            var tasks=context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
            assertThat(tasks).hasSize(1);
            var task=(FixedDelayTask)tasks.iterator().next().getTask();
            assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofSeconds(60));
            assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofSeconds(60));
            var annotation=VisitCollectionConfiguration.VisitCollectionLifecycle.class.getMethod("cleanup").getAnnotation(Scheduled.class);
            assertThat(annotation.scheduler()).isEqualTo("visitCollectionScheduler");
            verifyNoInteractions(context.getBean("writeJdbc"),context.getBean("writeTransactionManager"),context.getBean("readClock"));
            var handler=(ErrorHandler)ReflectionTestUtils.getField(scheduler,"errorHandler");
            handler.handleError(new IllegalStateException("synthetic-secret-cookie",new RuntimeException("synthetic-private-sql")));
            assertThat(output.getAll()).contains("Visit collection cleanup failed (IllegalStateException)")
                    .doesNotContain("synthetic-secret-cookie","synthetic-private-sql");
        });
        var service=mock(VisitCollection.class);
        var lifecycle=new VisitCollectionConfiguration.VisitCollectionLifecycle(service);
        lifecycle.start(); lifecycle.cleanup();
        var order=inOrder(service);order.verify(service).start();order.verify(service,times(2)).cleanup();
    }
    private ApplicationContextRunner inputs(boolean key) {
        var runner=new ApplicationContextRunner().withUserConfiguration(VisitCollectionConfiguration.class)
                .withPropertyValues("pawbridge.visits.enabled=true","pawbridge.visits.collection-enabled=true")
                .withInitializer(context -> {
                    context.getBeanFactory().registerSingleton("writeDataSource",new Object());
                    context.getBeanFactory().registerSingleton("writeJdbc",mock(JdbcTemplate.class));
                    context.getBeanFactory().registerSingleton("writeTransactionManager",mock(JdbcTransactionManager.class));
                    context.getBeanFactory().registerSingleton("readClock",mock(Clock.class));
                });
        return key?runner.withPropertyValues("pawbridge.visits.signing-key="+Base64.getEncoder().encodeToString(new byte[32])):runner;
    }
}
