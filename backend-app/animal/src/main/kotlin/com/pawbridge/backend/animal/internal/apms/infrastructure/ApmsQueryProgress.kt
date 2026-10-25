package com.pawbridge.backend.animal.internal.apms.infrastructure

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution

/** Collection evidence only. A query becomes a checkpoint after ingestion and indexing succeed. */
object ApmsQueryProgress {
    const val BACKEND_KEY: String = "apms.sync.searchBackend"
    const val CONTEXT_KEY: String = "apms.sync.queryResults"

    // 이 mapper는 Boot의 mapper가 아니라 Kotlin 모듈이 없는 일반 ObjectMapper다(Java 원본과 동일).
    private val JSON: ObjectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val POSTGRESQL_DATA_STEPS: List<String> = java.util.List.of("shelterPrepStep", "apmsAnimalSyncStep")
    private val DATA_STEPS: List<String> =
        java.util.List.of("shelterPrepStep", "apmsAnimalSyncStep", "elasticsearchIndexStep")

    @JvmRecord
    data class Result(val query: ApmsSyncPlan.Query, val complete: Boolean)

    @JvmRecord
    data class Evidence(val version: Int, val results: List<Result>?)

    @JvmStatic
    fun store(execution: JobExecution, results: List<Result>) {
        try {
            execution.executionContext.putString(CONTEXT_KEY, JSON.writeValueAsString(Evidence(1, results)))
        } catch (exception: JsonProcessingException) {
            throw IllegalStateException("Cannot serialize APMS query results", exception)
        }
    }

    @JvmStatic
    fun verifiedResults(execution: JobExecution, plan: ApmsSyncPlan): List<Result> {
        if (execution.status != BatchStatus.COMPLETED && execution.status != BatchStatus.FAILED) {
            return java.util.List.of()
        }
        val backend = execution.executionContext.getString(BACKEND_KEY, "elasticsearch")
        if (backend != "elasticsearch" && backend != "postgresql") {
            return java.util.List.of()
        }
        val dataSteps = if (backend == "postgresql") POSTGRESQL_DATA_STEPS else DATA_STEPS
        if (execution.stepExecutions.size != dataSteps.size + 1) {
            return java.util.List.of()
        }
        if (execution.stepExecutions.any { step -> step.skipCount != 0L }) {
            return java.util.List.of()
        }
        for (name in dataSteps) {
            val steps = execution.stepExecutions.filter { step -> name == step.stepName }
            if (steps.size != 1 || steps[0].status != BatchStatus.COMPLETED) {
                return java.util.List.of()
            }
        }
        val verification = execution.stepExecutions
            .filter { step -> "apmsCollectionVerificationStep" == step.stepName }
        if (verification.size != 1 || verification[0].status != execution.status) {
            return java.util.List.of()
        }
        // Unknown or malformed evidence never advances recovery. The original plan remains retryable.
        try {
            val context = execution.executionContext
            val evidence = JSON.readValue(context.getString(CONTEXT_KEY), Evidence::class.java)
            val results = evidence.results
            if (evidence.version != 1 ||
                results == null ||
                results.map { result -> result.query } != plan.queries()
            ) {
                return java.util.List.of()
            }
            val incomplete = results.count { result -> !result.complete }.toLong()
            if (incomplete != context.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT).toLong()) {
                return java.util.List.of()
            }
            if (execution.status == BatchStatus.FAILED && incomplete == 0L) {
                return java.util.List.of()
            }
            return java.util.List.copyOf(results)
        } catch (exception: RuntimeException) {
            return java.util.List.of()
        } catch (exception: JsonProcessingException) {
            return java.util.List.of()
        }
    }
}
