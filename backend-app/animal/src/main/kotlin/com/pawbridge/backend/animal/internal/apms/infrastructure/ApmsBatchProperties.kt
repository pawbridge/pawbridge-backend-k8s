package com.pawbridge.backend.animal.internal.apms.infrastructure

import java.time.Duration

/** The original APMS stale-execution policy, bound explicitly by Boot. */
class ApmsBatchProperties {
    private var staleExecutionThreshold: Duration = Duration.ofMinutes(10)

    // Spring Boot's JavaBean binder uses these accessors; the setter accepted (and rejected) null in Java.
    fun getStaleExecutionThreshold(): Duration = staleExecutionThreshold

    fun setStaleExecutionThreshold(value: Duration?) {
        if (value == null || value.isZero || value.isNegative) {
            throw IllegalArgumentException("staleExecutionThreshold must be positive")
        }
        staleExecutionThreshold = value
    }
}
