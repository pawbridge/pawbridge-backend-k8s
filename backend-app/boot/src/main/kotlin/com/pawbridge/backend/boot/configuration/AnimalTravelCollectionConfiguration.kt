package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.PetTravelCollector
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.PostgresqlTravelCollectionCatalog
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.TourApiClient
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.TourApiProperties
import com.zaxxer.hikari.HikariDataSource
import java.time.Clock
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.interceptor.DefaultTransactionAttribute

/** Explicit original collection job; query switches cannot create a client, job or scheduler. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "tourapi", name = ["enabled"], havingValue = "true")
@Import(AnimalTravelCollectionSchedule::class)
class AnimalTravelCollectionConfiguration {
    @Bean
    @ConfigurationProperties(prefix = "tourapi")
    fun tourApiProperties(): TourApiProperties {
        return TourApiProperties()
    }

    @Bean
    fun tourApiClient(properties: TourApiProperties, mapper: ObjectMapper): TourApiClient {
        return TourApiClient(properties, mapper)
    }

    @Bean
    fun travelCollectionCatalog(
        @Qualifier("writeDataSource") source: HikariDataSource,
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
        mapper: ObjectMapper,
    ): PostgresqlTravelCollectionCatalog {
        if (source.maximumPoolSize < 2 || jdbc.dataSource !== source || manager.dataSource !== source) {
            throw IllegalArgumentException("Travel collection requires the same writer with capacity of at least two")
        }
        return PostgresqlTravelCollectionCatalog(jdbc, mapper, manager)
    }

    @Bean
    fun petTravelCollector(
        @Qualifier("writeDataSource") source: HikariDataSource,
        catalog: PostgresqlTravelCollectionCatalog,
        client: TourApiClient,
        properties: TourApiProperties,
        @Qualifier("readClock") clock: Clock,
    ): PetTravelCollector {
        return PetTravelCollector(source, catalog, client, properties, clock)
    }

    @Bean
    @Throws(Exception::class)
    fun travelCollectionJobRepository(
        @Qualifier("writeDataSource") source: HikariDataSource,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
        requiredCatalog: PostgresqlTravelCollectionCatalog,
    ): JobRepository {
        // Explicit type and schema avoid JDBC metadata access, search_path assumptions and schema creation.
        val factory = JobRepositoryFactoryBean()
        factory.setDataSource(source)
        factory.transactionManager = manager
        factory.setDatabaseType("POSTGRES")
        factory.setTablePrefix("pawbridge_animal.BATCH_")
        factory.setIsolationLevelForCreate("ISOLATION_SERIALIZABLE")
        factory.afterPropertiesSet()
        return factory.getObject()
    }

    @Bean
    fun petTravelCollectionJob(
        @Qualifier("travelCollectionJobRepository") repository: JobRepository,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
        collector: PetTravelCollector,
    ): Job {
        val noTransaction = DefaultTransactionAttribute()
        noTransaction.propagationBehavior = TransactionDefinition.PROPAGATION_NOT_SUPPORTED
        val collectTasklet = Tasklet { contribution, _ ->
            val result = collector.collect()
            contribution.exitStatus = ExitStatus(result.status)
            RepeatStatus.FINISHED
        }
        val step = StepBuilder("petTravelCollectionStep", repository)
            .tasklet(collectTasklet, manager)
            .transactionAttribute(noTransaction)
            .build()
        return JobBuilder("petTravelCollectionJob", repository).start(step).build()
    }
}
