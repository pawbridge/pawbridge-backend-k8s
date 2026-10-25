package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveProperties;
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveWorker;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** One owner, cooperative stop before dependent HTTP/S3 clients are destroyed. */
public final class PhotoArchiveSchedule implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(PhotoArchiveSchedule.class);
    private final PhotoArchiveWorker worker;
    private final PhotoArchiveProperties properties;
    private final int shutdownSeconds;
    private ScheduledThreadPoolExecutor executor;
    private ScheduledFuture<?> future;
    private volatile boolean running;
    private volatile boolean closing;

    PhotoArchiveSchedule(PhotoArchiveWorker worker, PhotoArchiveProperties properties, int shutdownSeconds) {
        if (shutdownSeconds < 1 || shutdownSeconds > 300)
            throw new IllegalArgumentException("Photo archive shutdown timeout must be between 1 and 300 seconds");
        this.worker = worker;
        this.properties = properties;
        this.shutdownSeconds = shutdownSeconds;
    }

    @Override public synchronized void start() {
        if (running || closing) return;
        worker.resume();
        executor = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "apms-photo-archive");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        running = true;
        future = executor.scheduleWithFixedDelay(worker::scheduled, properties.getInitialDelayMs(),
                properties.getIntervalMs(), TimeUnit.MILLISECONDS);
    }

    @Override public void stop() {
        ScheduledThreadPoolExecutor stopping;
        synchronized (this) {
            closing = true;
            worker.requestStop();
            stopping = executor;
            if (stopping == null) { running = false; return; }
            if (future != null) future.cancel(false);
            stopping.shutdown();
        }
        boolean terminated = false;
        try { terminated = stopping.awaitTermination(shutdownSeconds, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (!terminated) {
            stopping.shutdownNow();
            log.warn("APMS photo archive shutdown incomplete; lease recovery may be required");
        }
        running = false;
    }

    @Override public void stop(Runnable callback) { try { stop(); } finally { callback.run(); } }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
}
