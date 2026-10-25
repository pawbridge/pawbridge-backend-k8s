package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.mapper.AnimalMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.LostSearchService
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.PythonLostSearchClient
import com.pawbridge.backend.animal.internal.lostsearch.web.LostSearchController
import jakarta.persistence.EntityManagerFactory
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter

/**
 * Opt-in read-only persistence; inference completes before the service opens its short read transaction.
 * Public activation additionally requires the bounded upload admission documented in the runbook.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.animal-lost-search", name = ["enabled"], havingValue = "true")
class AnimalLostSearchConfiguration {
    @Bean
    fun animalLostSearchEntityManagerFactory(
        @Qualifier("dataSource") source: DataSource,
    ): LocalContainerEntityManagerFactoryBean {
        val factory = LocalContainerEntityManagerFactoryBean()
        factory.setDataSource(source)
        factory.setPersistenceUnitName("pawbridge-animal-lost-search-reader")
        factory.setManagedTypes(PersistenceManagedTypes.of(MODEL + "Animal", MODEL + "Shelter", MODEL + "BaseTimeEntity"))
        factory.jpaVendorAdapter = HibernateJpaVendorAdapter()
        factory.setJpaPropertyMap(
            mapOf(
                "hibernate.dialect" to "org.hibernate.dialect.PostgreSQLDialect",
                "hibernate.boot.allow_jdbc_metadata_access" to false,
                "hibernate.hbm2ddl.auto" to "none",
                "jakarta.persistence.schema-generation.database.action" to "none",
                "hibernate.default_schema" to "pawbridge_animal",
                "hibernate.jdbc.time_zone" to "UTC",
                "hibernate.physical_naming_strategy" to "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
            ),
        )
        return factory
    }

    @Bean
    fun animalLostSearchTransactions(
        @Qualifier("animalLostSearchEntityManagerFactory") factory: EntityManagerFactory,
        @Qualifier("dataSource") source: DataSource,
    ): JpaTransactionManager {
        val transactions = JpaTransactionManager(factory)
        transactions.dataSource = source
        transactions.defaultTimeout = 5
        return transactions
    }

    @Bean
    fun animalLostSearchPython(
        @Value("\${lost-search.python-url:\${python-ai-service.url}}") url: String,
        mapper: ObjectMapper,
    ): PythonLostSearchClient {
        return PythonLostSearchClient(url, mapper)
    }

    @Bean
    fun animalLostSearchService(
        @Qualifier("animalLostSearchPython") client: PythonLostSearchClient,
        @Qualifier("animalLostSearchEntityManagerFactory") factory: EntityManagerFactory,
        @Value("\${python-ai-service.internal-api-key:}") key: String,
        @Qualifier("animalLostSearchTransactions") transactions: JpaTransactionManager,
    ): LostSearchService {
        val sharedEntityManager = SharedEntityManagerCreator.createSharedEntityManager(factory)
        val animals = JpaRepositoryFactory(sharedEntityManager).getRepository(AnimalRepository::class.java)
        return LostSearchService(client, animals, AnimalMapper(), key, transactions)
    }

    @Bean
    fun animalLostSearchController(service: LostSearchService): LostSearchController {
        return LostSearchController(service)
    }

    private companion object {
        const val MODEL = "com.pawbridge.backend.animal.internal.command.infrastructure.entity."
    }
}
