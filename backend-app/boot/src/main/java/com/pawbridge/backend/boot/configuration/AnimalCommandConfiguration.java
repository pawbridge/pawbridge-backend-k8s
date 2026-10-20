package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.api.AnimalCommandActors;
import com.pawbridge.backend.animal.internal.command.infrastructure.mapper.AnimalMapper;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.OutboxEventRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter;
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService;
import com.pawbridge.backend.animal.internal.command.infrastructure.service.NoticeNumberGenerator;
import com.pawbridge.backend.animal.internal.command.infrastructure.service.OutboxService;
import com.pawbridge.backend.animal.internal.command.web.AnimalCommandController;
import com.pawbridge.backend.animal.internal.command.web.AnimalCommandExceptionHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.JpaTransactionManager;

/** One opt-in writer persistence unit; no entity/repository scan or additional pool. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.animal-commands", name = "enabled", havingValue = "true")
@Import(AnimalJpaWriterConfiguration.class)
public class AnimalCommandConfiguration {
    @Bean AnimalMapper animalCommandMapper() { return new AnimalMapper(); }

    @Bean
    OutboxService animalCommandOutbox(OutboxEventRepository repository, ObjectMapper mapper) {
        return new OutboxService(repository, mapper);
    }

    @Bean
    NoticeNumberGenerator animalCommandNoticeNumbers(AnimalRepository animals) { return new NoticeNumberGenerator(animals); }

    @Bean
    AnimalCommandService animalCommandService(SearchDocumentWriter search, AnimalRepository animals,
            ShelterRepository shelters, AnimalMapper mapper, OutboxService outbox,
            NoticeNumberGenerator numbers, AnimalCommandActors actors,
            @Qualifier("animalCommandJpaTransactionManager") JpaTransactionManager manager) {
        return new AnimalCommandService(search, animals, shelters, mapper, outbox, numbers, actors, manager);
    }

    @Bean AnimalCommandController animalCommandController(AnimalCommandService animals) { return new AnimalCommandController(animals); }
    @Bean AnimalCommandExceptionHandler animalCommandExceptionHandler() { return new AnimalCommandExceptionHandler(); }
}
