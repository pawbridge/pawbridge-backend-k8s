package com.pawbridge.animalservice.photo;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

/** A private scheduler: photo I/O never occupies the application's shared @Scheduled thread. */
public final class PhotoArchiveSchedule implements SmartLifecycle {
    private final PhotoArchiveWorker worker;
    private final PhotoArchiveProperties properties;
    private ScheduledExecutorService executor;
    public PhotoArchiveSchedule(PhotoArchiveWorker worker, PhotoArchiveProperties properties) {
        this.worker=worker; this.properties=properties;
    }
    @Override public synchronized void start() {
        if (isRunning()) return;
        executor=Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread=new Thread(task,"apms-photo-archive");thread.setDaemon(true);return thread;
        });
        executor.scheduleWithFixedDelay(worker::scheduled,properties.getInitialDelayMs(),properties.getIntervalMs(),TimeUnit.MILLISECONDS);
    }
    @Override public synchronized void stop() { if (executor != null) executor.shutdownNow(); }
    @Override public synchronized boolean isRunning() { return executor != null && !executor.isShutdown(); }
}
