package com.pawbridge.animalservice.batch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "apms.batch")
public class ApmsBatchProperties {
    private Duration staleExecutionThreshold = Duration.ofMinutes(10);
    private Duration elasticsearchIndexTimeout = Duration.ofMinutes(4);
    private Duration elasticsearchCancellationWait = Duration.ofSeconds(35);
    private int elasticsearchParallelism = 4;

    public Duration getStaleExecutionThreshold() {
        return staleExecutionThreshold;
    }

    public void setStaleExecutionThreshold(Duration staleExecutionThreshold) {
        this.staleExecutionThreshold = requirePositive(staleExecutionThreshold, "staleExecutionThreshold");
    }

    public Duration getElasticsearchIndexTimeout() {
        return elasticsearchIndexTimeout;
    }

    public void setElasticsearchIndexTimeout(Duration elasticsearchIndexTimeout) {
        this.elasticsearchIndexTimeout = requirePositive(elasticsearchIndexTimeout, "elasticsearchIndexTimeout");
    }

    public Duration getElasticsearchCancellationWait() {
        return elasticsearchCancellationWait;
    }

    public void setElasticsearchCancellationWait(Duration elasticsearchCancellationWait) {
        this.elasticsearchCancellationWait = requirePositive(elasticsearchCancellationWait, "elasticsearchCancellationWait");
    }

    public int getElasticsearchParallelism() {
        return elasticsearchParallelism;
    }

    public void setElasticsearchParallelism(int elasticsearchParallelism) {
        if (elasticsearchParallelism < 1) {
            throw new IllegalArgumentException("elasticsearchParallelism must be positive");
        }
        this.elasticsearchParallelism = elasticsearchParallelism;
    }

    private Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
