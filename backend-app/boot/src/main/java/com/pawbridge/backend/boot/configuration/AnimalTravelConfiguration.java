package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.travel.application.PetTravel;
import com.pawbridge.backend.animal.internal.travel.infrastructure.PostgresqlPetTravel;
import com.pawbridge.backend.animal.internal.travel.web.PetTravelController;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Only the three catalog GETs. Existing reader resources are supplied by ReadConfiguration. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix="pawbridge.animal-travel",name="enabled",havingValue="true")
public class AnimalTravelConfiguration {
    public AnimalTravelConfiguration(@Value("${pawbridge.animal-query.backend:}") String backend) {
        if(!"postgresql".equals(backend)) throw new IllegalArgumentException("Travel catalog requires explicit PostgreSQL selection");
    }
    @Bean
    TransactionTemplate animalTravelListReadOnlyTransaction(@Qualifier("readOnlyTransaction") TransactionTemplate reads) {
        if(!reads.isReadOnly() || reads.getTransactionManager()==null)
            throw new IllegalArgumentException("Travel catalog requires the existing reader read-only transaction");
        var repeatable=new TransactionTemplate(reads.getTransactionManager(),reads);
        repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return repeatable;
    }
    @Bean
    PetTravel animalTravel(@Qualifier("readJdbc") JdbcTemplate jdbc,
            @Qualifier("readOnlyTransaction") TransactionTemplate reads,
            @Qualifier("animalTravelListReadOnlyTransaction") TransactionTemplate repeatable,ObjectMapper mapper) {
        return new PostgresqlPetTravel(jdbc,mapper,reads,repeatable);
    }
    @Bean PetTravelController petTravelController(PetTravel travel) { return new PetTravelController(travel); }
}
