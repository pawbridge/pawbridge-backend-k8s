package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure.ShelterDirectoryClient
import com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure.ShelterDirectoryCollector
import com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure.ShelterDirectoryStore
import com.zaxxer.hikari.HikariDataSource
import org.springframework.batch.core.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.interceptor.DefaultTransactionAttribute

/** Original shelter directory job and schedule, assembled only by the original schedule switch. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "shelter-directory", name = ["schedule-enabled"], havingValue = "true")
@Import(AnimalSearchConfiguration::class, ShelterDirectorySchedule::class)
class ShelterDirectoryConfiguration {
    @Bean
    fun shelterDirectoryClient(
        @Value("\${shelter-directory.service-key:}") key: String,
        mapper: ObjectMapper,
    ): ShelterDirectoryClient {
        return ShelterDirectoryClient(key, mapper)
    }

    @Bean
    fun shelterDirectoryStore(
        mapper: ObjectMapper,
        @Qualifier("writeDataSource") source: HikariDataSource,
        analyzer: KoreanSearchAnalyzer,
    ): ShelterDirectoryStore {
        // Not a bean: existing Animal writes inject the single SearchDocumentWriter by type.
        return ShelterDirectoryStore(mapper, SearchDocumentWriter(source, analyzer))
    }

    @Bean
    fun shelterDirectoryCollector(
        @Qualifier("writeDataSource") source: HikariDataSource,
        client: ShelterDirectoryClient,
        store: ShelterDirectoryStore,
    ): ShelterDirectoryCollector {
        return ShelterDirectoryCollector(source, client, store)
    }

    @Bean
    @Throws(Exception::class)
    fun shelterDirectoryJobRepository(
        @Qualifier("writeDataSource") source: HikariDataSource,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
    ): JobRepository {
        if (manager.dataSource !== source) {
            throw IllegalArgumentException("Shelter directory must use the same existing writer DataSource")
        }
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
    fun shelterDirectoryJob(
        @Qualifier("shelterDirectoryJobRepository") repository: JobRepository,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
        collector: ShelterDirectoryCollector,
    ): Job {
        val noTransaction = DefaultTransactionAttribute()
        noTransaction.propagationBehavior = TransactionDefinition.PROPAGATION_NOT_SUPPORTED
        val collectTasklet = Tasklet { contribution, _ ->
            val result = collector.collect()
            contribution.incrementWriteCount(result.toLong())
            RepeatStatus.FINISHED
        }
        val step = StepBuilder("shelterDirectoryStep", repository)
            .tasklet(collectTasklet, manager)
            .transactionAttribute(noTransaction)
            .build()
        return JobBuilder("shelterDirectoryJob", repository).start(step).build()
    }
}
