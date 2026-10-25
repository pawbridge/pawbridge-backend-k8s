package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.travel.application.PetTravel
import com.pawbridge.backend.animal.internal.travel.infrastructure.PostgresqlPetTravel
import com.pawbridge.backend.animal.internal.travel.web.PetTravelController
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** Only the three catalog GETs. Existing reader resources are supplied by ReadConfiguration. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.animal-travel", name = ["enabled"], havingValue = "true")
class AnimalTravelConfiguration(
    @Value("\${pawbridge.animal-query.backend:}") backend: String,
) {
    init {
        if ("postgresql" != backend) {
            throw IllegalArgumentException("Travel catalog requires explicit PostgreSQL selection")
        }
    }

    @Bean
    fun animalTravelListReadOnlyTransaction(
        @Qualifier("readOnlyTransaction") reads: TransactionTemplate,
    ): TransactionTemplate {
        if (!reads.isReadOnly || reads.transactionManager == null) {
            throw IllegalArgumentException("Travel catalog requires the existing reader read-only transaction")
        }
        val repeatable = TransactionTemplate(reads.transactionManager!!, reads)
        repeatable.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        return repeatable
    }

    @Bean
    fun animalTravel(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") reads: TransactionTemplate,
        @Qualifier("animalTravelListReadOnlyTransaction") repeatable: TransactionTemplate,
        mapper: ObjectMapper,
    ): PetTravel {
        return PostgresqlPetTravel(jdbc, mapper, reads, repeatable)
    }

    @Bean
    fun petTravelController(travel: PetTravel): PetTravelController {
        return PetTravelController(travel)
    }
}
