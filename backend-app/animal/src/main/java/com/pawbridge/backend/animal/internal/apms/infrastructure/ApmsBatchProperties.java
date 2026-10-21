package com.pawbridge.backend.animal.internal.apms.infrastructure;

import java.time.Duration;

/** The original APMS stale-execution policy, bound explicitly by Boot. */
public class ApmsBatchProperties {
    private Duration staleExecutionThreshold = Duration.ofMinutes(10);
    public Duration getStaleExecutionThreshold() { return staleExecutionThreshold; }
    public void setStaleExecutionThreshold(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException("staleExecutionThreshold must be positive");
        staleExecutionThreshold = value;
    }
}
