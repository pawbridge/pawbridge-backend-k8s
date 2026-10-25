package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveProperties
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveWorker
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle

/** One owner, cooperative stop before dependent HTTP/S3 clients are destroyed. */
class PhotoArchiveSchedule(
    private val worker: PhotoArchiveWorker,
    private val properties: PhotoArchiveProperties,
    private val shutdownSeconds: Int,
) : SmartLifecycle {
    private var executor: ScheduledThreadPoolExecutor? = null
    private var future: ScheduledFuture<*>? = null

    @Volatile
    private var running = false

    @Volatile
    private var closing = false

    init {
        if (shutdownSeconds < 1 || shutdownSeconds > 300) {
            throw IllegalArgumentException("Photo archive shutdown timeout must be between 1 and 300 seconds")
        }
    }

    @Synchronized
    override fun start() {
        if (running || closing) {
            return
        }
        worker.resume()
        val created = ScheduledThreadPoolExecutor(
            1,
            ThreadFactory { task ->
                val thread = Thread(task, "apms-photo-archive")
                thread.isDaemon = true
                thread
            },
        )
        created.removeOnCancelPolicy = true
        created.executeExistingDelayedTasksAfterShutdownPolicy = false
        created.continueExistingPeriodicTasksAfterShutdownPolicy = false
        executor = created
        running = true
        future = created.scheduleWithFixedDelay(
            Runnable { worker.scheduled() },
            properties.initialDelayMs,
            properties.intervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    override fun stop() {
        val stopping: ScheduledThreadPoolExecutor = synchronized(this) {
            closing = true
            worker.requestStop()
            val current = executor
            if (current == null) {
                running = false
                return
            }
            future?.cancel(false)
            current.shutdown()
            current
        }
        var terminated = false
        try {
            terminated = stopping.awaitTermination(shutdownSeconds.toLong(), TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (!terminated) {
            stopping.shutdownNow()
            log.warn("APMS photo archive shutdown incomplete; lease recovery may be required")
        }
        running = false
    }

    override fun stop(callback: Runnable) {
        try {
            stop()
        } finally {
            callback.run()
        }
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = Int.MAX_VALUE

    private companion object {
        private val log = LoggerFactory.getLogger(PhotoArchiveSchedule::class.java)
    }
}
