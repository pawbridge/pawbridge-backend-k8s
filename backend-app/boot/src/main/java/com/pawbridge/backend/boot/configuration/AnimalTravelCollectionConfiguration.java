package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.PetTravelCollector;
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.PostgresqlTravelCollectionCatalog;
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.TourApiClient;
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.TourApiProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

/** Explicit original collection job; query switches cannot create a client, job or scheduler. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "tourapi", name = "enabled", havingValue = "true")
@Import(AnimalTravelCollectionSchedule.class)
public class AnimalTravelCollectionConfiguration {
    @Bean
    @ConfigurationProperties(prefix = "tourapi")
    TourApiProperties tourApiProperties() { return new TourApiProperties(); }

    @Bean
    TourApiClient tourApiClient(TourApiProperties properties, ObjectMapper mapper) {
        return new TourApiClient(properties, mapper);
    }

    @Bean
    PostgresqlTravelCollectionCatalog travelCollectionCatalog(
            @Qualifier("writeDataSource") HikariDataSource source,
            @Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager, ObjectMapper mapper) {
        if (source.getMaximumPoolSize() < 2 || jdbc.getDataSource() != source || manager.getDataSource() != source)
            throw new IllegalArgumentException("Travel collection requires the same writer with capacity of at least two");
        return new PostgresqlTravelCollectionCatalog(jdbc, mapper, manager);
    }

    @Bean
    PetTravelCollector petTravelCollector(@Qualifier("writeDataSource") HikariDataSource source,
            PostgresqlTravelCollectionCatalog catalog, TourApiClient client, TourApiProperties properties,
            @Qualifier("readClock") Clock clock) {
        return new PetTravelCollector(source, catalog, client, properties, clock);
    }

    @Bean
    JobRepository travelCollectionJobRepository(@Qualifier("writeDataSource") HikariDataSource source,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager,
            PostgresqlTravelCollectionCatalog requiredCatalog) throws Exception {
        // Explicit type and schema avoid JDBC metadata access, search_path assumptions and schema creation.
        var factory = new JobRepositoryFactoryBean();
        factory.setDataSource(source); factory.setTransactionManager(manager);
        factory.setDatabaseType("POSTGRES"); factory.setTablePrefix("pawbridge_animal.BATCH_");
        factory.setIsolationLevelForCreate("ISOLATION_SERIALIZABLE");
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    @Bean
    Job petTravelCollectionJob(@Qualifier("travelCollectionJobRepository") JobRepository repository,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager, PetTravelCollector collector) {
        var noTransaction = new DefaultTransactionAttribute();
        noTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var step = new StepBuilder("petTravelCollectionStep", repository)
                .tasklet((contribution, context) -> {
                    var result = collector.collect();
                    contribution.setExitStatus(new ExitStatus(result.status()));
                    return RepeatStatus.FINISHED;
                }, manager).transactionAttribute(noTransaction).build();
        return new JobBuilder("petTravelCollectionJob", repository).start(step).build();
    }
}
