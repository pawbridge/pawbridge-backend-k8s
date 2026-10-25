package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.api.AnimalShelterReads
import com.pawbridge.backend.animal.internal.memberread.infrastructure.JdbcAnimalMemberReads
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterReads
import com.pawbridge.backend.animal.internal.shelter.infrastructure.ModuleAnimalShelterReads
import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/** Imported only from enabled User feature configurations; never enables public routes. */
@Configuration(proxyBeanMethods = false)
internal class AnimalModuleReadConfiguration {
    @Bean
    fun animalModuleReads(
        @Qualifier("dataSource") source: DataSource,
        @Qualifier("transactionManager") manager: PlatformTransactionManager,
        mapper: ObjectMapper,
        existing: ObjectProvider<ShelterReads>,
        @Value("\${pawbridge.user-animal-activities.enabled:false}") activities: Boolean,
    ): AnimalShelterReads {
        val shelters: ShelterReads = existing.getIfAvailable { JdbcShelterReads(source, manager, mapper) }
        val registration = ModuleAnimalShelterReads(shelters, manager)
        if (activities) {
            return JdbcAnimalMemberReads(source, manager, registration)
        }
        return registration
    }
}
