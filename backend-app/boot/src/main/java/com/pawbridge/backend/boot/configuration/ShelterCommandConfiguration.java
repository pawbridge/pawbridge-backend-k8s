package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.animal.api.AnimalCommandActors;
import com.pawbridge.backend.animal.api.AnimalShelterMembers;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter;
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommands;
import com.pawbridge.backend.animal.internal.sheltercommand.infrastructure.JpaShelterCommands;
import com.pawbridge.backend.animal.internal.sheltercommand.web.ShelterCommandController;
import com.pawbridge.backend.animal.internal.sheltercommand.web.ShelterCommandExceptionHandler;
import com.pawbridge.backend.user.internal.shelter.infrastructure.JdbcAnimalShelterMembers;
import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Three opt-in shelter writes sharing the existing Animal writer and User membership locks. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.shelter-commands",name="enabled",havingValue="true")
@Import(AnimalJpaWriterConfiguration.class)
public class ShelterCommandConfiguration {
    public ShelterCommandConfiguration(
            @Value("${pawbridge.shelter-membership.enabled:false}") boolean membershipEnabled,
            @Value("${pawbridge.shelter-membership.animal-backend:http}") String membershipBackend) {
        if(membershipEnabled && !"module".equals(membershipBackend))
            throw new IllegalArgumentException("Shelter writes require the local membership backend when membership approval is enabled");
    }

    @Bean
    AnimalShelterMembers shelterCommandMembers(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeDataSource") DataSource source) {
        if(jdbc.getDataSource()!=source)
            throw new IllegalArgumentException("Shelter membership checks must use the same writer DataSource");
        return new JdbcAnimalShelterMembers(jdbc);
    }

    @Bean
    ShelterCommands shelterCommands(@Qualifier("animalCommandEntityManager") EntityManager entities,
            @Qualifier("animalCommandJpaTransactionManager") JpaTransactionManager manager,
            SearchDocumentWriter search,AnimalCommandActors actors,AnimalShelterMembers members) {
        var writes=new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return new JpaShelterCommands(entities,manager,writes,search,actors,members);
    }

    @Bean ShelterCommandController shelterCommandController(ShelterCommands shelters) {
        return new ShelterCommandController(shelters);
    }
    @Bean ShelterCommandExceptionHandler shelterCommandExceptionHandler() {
        return new ShelterCommandExceptionHandler();
    }
}
