package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads;
import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterReads;
import com.pawbridge.backend.animal.internal.shelter.web.ShelterDiscoveryReadController;
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadController;
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadExceptionHandler;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.animal-shelters", name = "enabled", havingValue = "true")
public class AnimalShelterConfiguration {
    @Bean
    ShelterReads shelterReads(@Qualifier("dataSource") DataSource source,
            @Qualifier("transactionManager") PlatformTransactionManager manager, ObjectMapper mapper) {
        return new JdbcShelterReads(source, manager, mapper);
    }

    @Bean
    ShelterReadController shelterReadController(ShelterReads shelters) { return new ShelterReadController(shelters); }

    @Bean
    @ConditionalOnProperty(prefix = "pawbridge.animal-query", name = "backend", havingValue = "postgresql")
    ShelterDiscoveryReadController shelterDiscoveryReadController(ShelterReads shelters) {
        return new ShelterDiscoveryReadController(shelters);
    }

    @Bean
    ShelterReadExceptionHandler shelterReadExceptionHandler() { return new ShelterReadExceptionHandler(); }
}
