package com.pawbridge.animalservice.photo;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PhotoArchiveWorker {
    private final PhotoArchiveStore store;
    private final PhotoArchiveHttpClient http;
    private final PhotoArchiveObjectStorage storage;
    private final PhotoArchiveProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();
    // Protected by running; retain alternation across runs, including maxPhotos=1.
    private boolean preferRecent = true;
    public record RunResult(int discoveredAnimals, int completed, int failed, int superseded) {}
    public PhotoArchiveWorker(PhotoArchiveStore store, PhotoArchiveHttpClient http,
                              PhotoArchiveObjectStorage storage, PhotoArchiveProperties properties) {
        this.store = store; this.http = http; this.storage = storage; this.properties = properties;
    }

    public void scheduled() {
        try {
            var result = runOnce();
            log.info("APMS photo archive: discovered={}, completed={}, failed={}, superseded={}",
                    result.discoveredAnimals(), result.completed(), result.failed(), result.superseded());
        } catch (RuntimeException failure) {
            // Database failure must be visible but must not affect the APMS collection job.
            log.error("APMS photo archive run failed: {}", failure.getClass().getSimpleName());
        }
    }

    public RunResult runOnce() {
        if (!running.compareAndSet(false, true)) return new RunResult(0,0,0,0);
        try {
            long deadline = System.nanoTime() + properties.getMaxRunSeconds() * 1_000_000_000L;
            int discovered = store.discover(properties.getScanSize());
            int completed = 0, failed = 0, superseded = 0;
            for (int i=0; i<properties.getMaxPhotos() && System.nanoTime()<deadline; i++) {
                var candidate = store.claim(properties.getLeaseSeconds(), preferRecent);
                preferRecent = !preferRecent;
                if (candidate.isEmpty()) break;
                var claim = candidate.get();
                try {
                    var photo = http.optimize(http.download(claim.sourceUrl()));
                    storage.saveAndVerify(photo);
                    if (store.complete(claim, photo, properties.getRecheckSeconds())) completed++;
                    else { store.retry(claim, "SOURCE_SUPERSEDED", 60); superseded++; }
                } catch (RuntimeException failure) {
                    PhotoArchiveFailure known = knownFailure(failure);
                    String code = known == null ? "ARCHIVE_IO" : known.getMessage();
                    int delay = known != null && known.slowRetry()
                            ? 86400 : retryDelay(claim.attempts());
                    // If this DB write fails, propagate; the lease still permits recovery after expiry.
                    store.retry(claim, code, delay);
                    failed++;
                }
                if (Thread.currentThread().isInterrupted()) break;
            }
            return new RunResult(discovered, completed, failed, superseded);
        } finally { running.set(false); }
    }
    private static PhotoArchiveFailure knownFailure(Throwable failure) {
        // SDK response transformers can wrap application failures. Preserve only our safe codes.
        for (int i=0; failure != null && i<10; i++, failure=failure.getCause())
            if (failure instanceof PhotoArchiveFailure known) return known;
        return null;
    }
    static int retryDelay(int attempts) { return (int) Math.min(3600L, 60L << Math.min(6, Math.max(0, attempts))); }
}
