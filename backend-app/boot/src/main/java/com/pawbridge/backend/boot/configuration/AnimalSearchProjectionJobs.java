package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.animal.internal.command.infrastructure.search.PostgresqlSearchProjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Each tick handles at most one page per type; zero rows is not a completion certificate. */
public final class AnimalSearchProjectionJobs {
    private static final Logger log = LoggerFactory.getLogger(AnimalSearchProjectionJobs.class);
    private final PostgresqlSearchProjector projector;
    private final int pageSize;

    public AnimalSearchProjectionJobs(PostgresqlSearchProjector projector, int pageSize) {
        if (pageSize < 1 || pageSize > 100)
            throw new IllegalArgumentException("Projection page must contain 1 to 100 rows");
        this.projector = java.util.Objects.requireNonNull(projector);
        this.pageSize = pageSize;
    }

    @Scheduled(scheduler = "animalSearchProjectionScheduler",
            fixedDelayString = "${pawbridge.animal-query.projection-delay-ms:2000}")
    public void refresh() {
        try {
            projector.refreshDirtyShelters(pageSize);
            projector.refreshDirtyAnimals(pageSize);
        } catch (RuntimeException failure) {
            log.warn("PostgreSQL search projection remains pending: {}", failure.getClass().getSimpleName());
        }
    }

    @Scheduled(scheduler = "animalSearchProjectionScheduler",
            fixedDelayString = "${pawbridge.animal-query.projection-audit-delay-ms:60000}",
            initialDelayString = "${pawbridge.animal-query.projection-audit-delay-ms:60000}")
    public void audit() {
        try {
            int shelters = projector.refreshShelters(pageSize);
            int animals = projector.refreshAnimals(pageSize);
            if (shelters > 0 || animals > 0)
                log.info("PostgreSQL search audit repaired animals={}, shelters={}", animals, shelters);
        } catch (RuntimeException failure) {
            log.warn("PostgreSQL search integrity audit incomplete: {}", failure.getClass().getSimpleName());
        }
    }
}
