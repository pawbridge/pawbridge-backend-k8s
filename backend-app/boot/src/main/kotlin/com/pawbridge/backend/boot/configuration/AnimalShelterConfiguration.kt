package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterReads
import com.pawbridge.backend.animal.internal.shelter.web.ShelterDiscoveryReadController
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadController
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadExceptionHandler
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.animal-shelters", name = ["enabled"], havingValue = "true")
class AnimalShelterConfiguration {
    @Bean
    fun shelterReads(
        @Qualifier("dataSource") source: DataSource,
        @Qualifier("transactionManager") manager: PlatformTransactionManager,
        mapper: ObjectMapper,
    ): ShelterReads {
        return JdbcShelterReads(source, manager, mapper)
    }

    @Bean
    fun shelterReadController(shelters: ShelterReads): ShelterReadController {
        return ShelterReadController(shelters)
    }

    @Bean
    @ConditionalOnProperty(prefix = "pawbridge.animal-query", name = ["backend"], havingValue = "postgresql")
    fun shelterDiscoveryReadController(shelters: ShelterReads): ShelterDiscoveryReadController {
        return ShelterDiscoveryReadController(shelters)
    }

    @Bean
    fun shelterReadExceptionHandler(): ShelterReadExceptionHandler {
        return ShelterReadExceptionHandler()
    }
}
