package com.pawbridge.backend.animal.internal.photoarchive.infrastructure

import org.slf4j.LoggerFactory
import java.util.Optional
import java.util.concurrent.atomic.AtomicBoolean

class PhotoArchiveWorker(
    private val store: PhotoArchiveStore,
    private val http: PhotoArchiveHttpClient,
    private val storage: PhotoArchiveObjectStorage,
    private val properties: PhotoArchiveProperties,
) {
    private val running = AtomicBoolean()

    // Stop and claim admission share this monitor; no network call holds it.
    private val lifecycle = Any()
    private var stopRequested = false

    // Protected by running; retain alternation across runs, including maxPhotos=1.
    private var preferRecent = true

    @JvmRecord
    data class RunResult(
        val discoveredAnimals: Int,
        val completed: Int,
        val failed: Int,
        val superseded: Int,
    )

    /** May wait for the current short claim transaction, but never interrupts an admitted item. */
    fun requestStop() {
        synchronized(lifecycle) {
            stopRequested = true
        }
    }

    fun resume() {
        synchronized(lifecycle) {
            if (running.get()) {
                throw IllegalStateException("PHOTO_ARCHIVE_RUNNING")
            }
            stopRequested = false
        }
    }

    fun scheduled() {
        try {
            val result = runOnce()
            log.info(
                "APMS photo archive: discovered={}, completed={}, failed={}, superseded={}",
                result.discoveredAnimals,
                result.completed,
                result.failed,
                result.superseded,
            )
        } catch (failure: RuntimeException) {
            // Database failure must be visible but must not affect the APMS collection job.
            log.error("APMS photo archive run failed: {}", failure.javaClass.simpleName)
        }
    }

    fun runOnce(): RunResult {
        synchronized(lifecycle) {
            if (stopRequested || !running.compareAndSet(false, true)) {
                return RunResult(0, 0, 0, 0)
            }
        }
        try {
            val deadline = System.nanoTime() + properties.maxRunSeconds * 1_000_000_000L
            val discovered = store.discover(properties.scanSize)
            var completed = 0
            var failed = 0
            var superseded = 0
            var index = 0
            while (index < properties.maxPhotos && System.nanoTime() < deadline) {
                var stopping = false
                var candidate: Optional<PhotoArchiveStore.Claim> = Optional.empty()
                synchronized(lifecycle) {
                    if (stopRequested) {
                        stopping = true
                    } else {
                        candidate = store.claim(properties.leaseSeconds, preferRecent)
                        preferRecent = !preferRecent
                    }
                }
                if (stopping) {
                    break
                }
                if (candidate.isEmpty) {
                    break
                }
                val claim = candidate.get()
                try {
                    val photo = http.optimize(http.download(claim.sourceUrl))
                    storage.saveAndVerify(photo)
                    if (store.complete(claim, photo, properties.recheckSeconds)) {
                        completed++
                    } else {
                        store.retry(claim, "SOURCE_SUPERSEDED", 60)
                        superseded++
                    }
                } catch (failure: RuntimeException) {
                    val known = knownFailure(failure)
                    val code = if (known == null) "ARCHIVE_IO" else known.message
                    val delay = if (known != null && known.slowRetry()) 86400 else retryDelay(claim.attempts)
                    // If this DB write fails, propagate; the lease still permits recovery after expiry.
                    store.retry(claim, code, delay)
                    failed++
                }
                if (Thread.currentThread().isInterrupted) {
                    break
                }
                index++
            }
            return RunResult(discovered, completed, failed, superseded)
        } finally {
            synchronized(lifecycle) {
                running.set(false)
            }
        }
    }

    private fun knownFailure(failure: Throwable): PhotoArchiveFailure? {
        // SDK response transformers can wrap application failures. Preserve only our safe codes.
        var current: Throwable? = failure
        var depth = 0
        while (current != null && depth < 10) {
            if (current is PhotoArchiveFailure) {
                return current
            }
            current = current.cause
            depth++
        }
        return null
    }

    companion object {
        private val log = LoggerFactory.getLogger(PhotoArchiveWorker::class.java)

        @JvmStatic
        fun retryDelay(attempts: Int): Int {
            return minOf(3600L, 60L shl minOf(6, maxOf(0, attempts))).toInt()
        }
    }
}
