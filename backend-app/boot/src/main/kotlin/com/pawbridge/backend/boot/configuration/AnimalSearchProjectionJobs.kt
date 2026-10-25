package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.command.infrastructure.search.PostgresqlSearchProjector
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

/** Each tick handles at most one page per type; zero rows is not a completion certificate. */
class AnimalSearchProjectionJobs(
    private val projector: PostgresqlSearchProjector,
    private val pageSize: Int,
) {
    init {
        if (pageSize < 1 || pageSize > 100) {
            throw IllegalArgumentException("Projection page must contain 1 to 100 rows")
        }
    }

    @Scheduled(
        scheduler = "animalSearchProjectionScheduler",
        fixedDelayString = "\${pawbridge.animal-query.projection-delay-ms:2000}",
    )
    fun refresh() {
        try {
            projector.refreshDirtyShelters(pageSize)
            projector.refreshDirtyAnimals(pageSize)
        } catch (failure: RuntimeException) {
            log.warn("PostgreSQL search projection remains pending: {}", failure.javaClass.simpleName)
        }
    }

    @Scheduled(
        scheduler = "animalSearchProjectionScheduler",
        fixedDelayString = "\${pawbridge.animal-query.projection-audit-delay-ms:60000}",
        initialDelayString = "\${pawbridge.animal-query.projection-audit-delay-ms:60000}",
    )
    fun audit() {
        try {
            val shelters = projector.refreshShelters(pageSize)
            val animals = projector.refreshAnimals(pageSize)
            if (shelters > 0 || animals > 0) {
                log.info("PostgreSQL search audit repaired animals={}, shelters={}", animals, shelters)
            }
        } catch (failure: RuntimeException) {
            log.warn("PostgreSQL search integrity audit incomplete: {}", failure.javaClass.simpleName)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(AnimalSearchProjectionJobs::class.java)
    }
}
