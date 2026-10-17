package com.pawbridge.animalservice.batch;

import java.time.ZoneId;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/** Opt-in APMS scheduling. A private thread keeps collection off the shared scheduler. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "apms.batch", name = "schedule-enabled", havingValue = "true")
public class ApmsCollectionSchedule implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ApmsCollectionSchedule.class);
    private final ApmsBatchRunner runner;
    private final CronTrigger trigger;
    private ThreadPoolTaskScheduler scheduler;
    private ScheduledFuture<?> future;

    public ApmsCollectionSchedule(ApmsBatchRunner runner,
            @Value("${apms.batch.collection-cron:0 */30 * * * *}") String cron) {
        this.runner = runner;
        this.trigger = new CronTrigger(cron, ZoneId.of("Asia/Seoul"));
    }

    @Override
    public synchronized void start() {
        if (isRunning()) return;
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("apms-collection-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setAwaitTerminationSeconds(330);
        scheduler.initialize();
        future = scheduler.schedule(this::collect, trigger);
    }

    @Override
    public synchronized void stop() {
        if (scheduler == null) return;
        // Stop future ticks; allow the synchronous runner to finish and release its DB lock.
        if (future != null) future.cancel(false);
        scheduler.shutdown();
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null && !scheduler.getScheduledThreadPoolExecutor().isShutdown();
    }

    void collect() {
        try {
            var execution = runner.run();
            long skipped = execution.getStepExecutions().stream().mapToLong(step -> step.getSkipCount()).sum();
            int incomplete = execution.getExecutionContext().getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, 0);
            if (execution.getStatus() != BatchStatus.COMPLETED || skipped != 0) {
                log.error("APMS scheduled collection failed: executionId={}, status={}, skipCount={}",
                        execution.getId(), execution.getStatus(), skipped);
            } else if (incomplete != 0) {
                // Existing COUNT_MISMATCH semantics: completed writes, retryable collection gaps.
                log.warn("APMS scheduled collection incomplete: executionId={}, incompleteQueryCount={}",
                        execution.getId(), incomplete);
            } else {
                log.info("APMS scheduled collection completed: executionId={}", execution.getId());
            }
        } catch (ApmsBatchRunner.AlreadyRunningException running) {
            log.info("APMS scheduled collection skipped: another execution owns the DB lock");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("APMS scheduled collection interrupted");
        } catch (Exception failure) {
            // Upstream exception text can include API credentials. Batch metadata holds job details.
            log.error("APMS scheduled collection failed: exceptionType={}", failure.getClass().getSimpleName());
        }
    }
}
