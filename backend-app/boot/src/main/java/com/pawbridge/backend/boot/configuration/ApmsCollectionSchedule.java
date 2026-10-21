package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsAnimalSnapshot;
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner;
import java.time.ZoneId;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/** One schedule owner; stop waits for the synchronous runner and its lock release. */
final class ApmsCollectionSchedule implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ApmsCollectionSchedule.class);
    private final ApmsBatchRunner runner;
    private final CronTrigger trigger;
    private ThreadPoolTaskScheduler scheduler;
    private ScheduledFuture<?> future;
    private volatile boolean running;
    private volatile boolean closing;

    ApmsCollectionSchedule(ApmsBatchRunner runner, String cron) {
        this.runner = runner;
        this.trigger = new CronTrigger(cron, ZoneId.of("Asia/Seoul"));
    }

    @Override public synchronized void start() {
        if (running || closing) return;
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("apms-collection-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        running = true;
        future = scheduler.schedule(this::collect, trigger);
    }

    @Override public void stop() {
        ThreadPoolTaskScheduler stopping;
        synchronized (this) {
            closing = true;
            stopping = scheduler;
            if (stopping == null) { running = false; return; }
            if (future != null) future.cancel(false);
            stopping.shutdown();
        }
        boolean interrupted = false;
        try {
            while (!stopping.getScheduledThreadPoolExecutor().isTerminated()) {
                try { stopping.getScheduledThreadPoolExecutor().awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally {
            running = false;
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }

    private void collect() {
        if (closing) return;
        try {
            var execution = runner.run();
            long skipped = execution.getStepExecutions().stream().mapToLong(step -> step.getSkipCount()).sum();
            int incomplete = execution.getExecutionContext().getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, 0);
            if (execution.getStatus() != BatchStatus.COMPLETED || skipped != 0)
                log.error("APMS collection failed: executionId={}, status={}, skipCount={}",
                        execution.getId(), execution.getStatus(), skipped);
            else if (incomplete != 0)
                log.warn("APMS collection incomplete: executionId={}, incompleteQueryCount={}", execution.getId(), incomplete);
            else log.info("APMS collection completed: executionId={}", execution.getId());
        } catch (ApmsBatchRunner.AlreadyRunningException busy) {
            log.info("APMS collection skipped: another execution owns the database lock");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("APMS collection interrupted");
        } catch (Exception failure) {
            log.error("APMS collection failed: exceptionType={}", failure.getClass().getSimpleName());
        }
    }
}
