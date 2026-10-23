package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.api.AnimalShelterReads;
import com.pawbridge.backend.animal.internal.memberread.infrastructure.JdbcAnimalMemberReads;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads;
import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterReads;
import com.pawbridge.backend.animal.internal.shelter.infrastructure.ModuleAnimalShelterReads;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Imported only from enabled User feature configurations; never enables public routes. */
@Configuration(proxyBeanMethods = false)
class AnimalModuleReadConfiguration {

    @Bean
    AnimalShelterReads animalModuleReads(
            @Qualifier("dataSource") DataSource source,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectMapper mapper,
            ObjectProvider<ShelterReads> existing,
            @Value("${pawbridge.user-animal-activities.enabled:false}") boolean activities) {
        ShelterReads shelters = existing.getIfAvailable(() -> new JdbcShelterReads(source, manager, mapper));
        var registration = new ModuleAnimalShelterReads(shelters, manager);
        if (activities) {
            return new JdbcAnimalMemberReads(source, manager, registration);
        }
        return registration;
    }
}
