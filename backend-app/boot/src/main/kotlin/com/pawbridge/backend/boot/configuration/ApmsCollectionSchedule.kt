package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsAnimalSnapshot
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner
import java.time.ZoneId
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.support.CronTrigger

/** One schedule owner; stop waits for the synchronous runner and its lock release. */
class ApmsCollectionSchedule(
    private val runner: ApmsBatchRunner,
    cron: String,
) : SmartLifecycle {
    private val trigger = CronTrigger(cron, ZoneId.of("Asia/Seoul"))
    private var scheduler: ThreadPoolTaskScheduler? = null
    private var future: ScheduledFuture<*>? = null

    @Volatile
    private var running = false

    @Volatile
    private var closing = false

    @Synchronized
    override fun start() {
        if (running || closing) {
            return
        }
        val created = ThreadPoolTaskScheduler()
        created.poolSize = 1
        created.setThreadNamePrefix("apms-collection-")
        created.setWaitForTasksToCompleteOnShutdown(true)
        created.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        created.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        created.setRemoveOnCancelPolicy(true)
        created.initialize()
        scheduler = created
        running = true
        future = created.schedule(Runnable { collect() }, trigger)
    }

    override fun stop() {
        val stopping: ThreadPoolTaskScheduler = synchronized(this) {
            closing = true
            val current = scheduler
            if (current == null) {
                running = false
                return
            }
            future?.cancel(false)
            current.shutdown()
            current
        }
        var interrupted = false
        try {
            while (!stopping.scheduledThreadPoolExecutor.isTerminated) {
                try {
                    stopping.scheduledThreadPoolExecutor.awaitTermination(1, TimeUnit.SECONDS)
                } catch (ignored: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            running = false
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun stop(callback: Runnable) {
        stop()
        callback.run()
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = Int.MAX_VALUE

    private fun collect() {
        if (closing) {
            return
        }
        try {
            val execution = runner.run()
            val skipped = execution.stepExecutions.sumOf { step -> step.skipCount.toLong() }
            val incomplete = execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, 0)
            if (execution.status != BatchStatus.COMPLETED || skipped != 0L) {
                log.error(
                    "APMS collection failed: executionId={}, status={}, skipCount={}",
                    execution.id,
                    execution.status,
                    skipped,
                )
            } else if (incomplete != 0) {
                log.warn("APMS collection incomplete: executionId={}, incompleteQueryCount={}", execution.id, incomplete)
            } else {
                log.info("APMS collection completed: executionId={}", execution.id)
            }
        } catch (busy: ApmsBatchRunner.AlreadyRunningException) {
            log.info("APMS collection skipped: another execution owns the database lock")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("APMS collection interrupted")
        } catch (failure: Exception) {
            log.error("APMS collection failed: exceptionType={}", failure.javaClass.simpleName)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ApmsCollectionSchedule::class.java)
    }
}
