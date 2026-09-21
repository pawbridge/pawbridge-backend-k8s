package com.pawbridge.animalservice.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(prefix="pawbridge.animal-query",name="projection-schedule-enabled",havingValue="true")
public class PostgresqlSearchProjectionSchedule {
    private static final Logger log=LoggerFactory.getLogger(PostgresqlSearchProjectionSchedule.class);
    private final PostgresqlSearchProjector projector;
    private final int pageSize;
    public PostgresqlSearchProjectionSchedule(PostgresqlSearchProjector projector,
            @Value("${pawbridge.animal-query.projection-page-size:50}") int pageSize) {
        if(pageSize<1 || pageSize>100) throw new IllegalArgumentException("Projection page must contain 1 to 100 rows");
        this.projector=projector;this.pageSize=pageSize;
    }
    @Scheduled(fixedDelayString="${pawbridge.animal-query.projection-delay-ms:2000}")
    public void refresh() {
        try { projector.refreshShelters(pageSize);projector.refreshAnimals(pageSize); }
        catch(RuntimeException failure) {
            // Pending source flags survive; no silent skip or false completion.
            log.warn("PostgreSQL search projection remains pending: {}",failure.getClass().getSimpleName());
        }
    }
}
