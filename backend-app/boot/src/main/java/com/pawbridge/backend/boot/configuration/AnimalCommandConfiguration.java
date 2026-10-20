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
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import com.pawbridge.backend.user.internal.animalcommand.infrastructure.JdbcAnimalCommandActors;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** One opt-in writer persistence unit; no entity/repository scan or additional pool. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.animal-commands", name = "enabled", havingValue = "true")
@EnableJpaAuditing
@Import(AnimalSearchConfiguration.class)
public class AnimalCommandConfiguration {
    private static final String MODEL = "com.pawbridge.backend.animal.internal.command.infrastructure.entity.";

    @Bean
    LocalContainerEntityManagerFactoryBean animalCommandEntityManagerFactory(
            @Qualifier("writeDataSource") DataSource source) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setPersistenceUnitName("pawbridge-animal-command-writer");
        factory.setManagedTypes(PersistenceManagedTypes.of(MODEL + "Animal", MODEL + "Shelter",
                MODEL + "OutboxEvent", MODEL + "BaseTimeEntity"));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect",
                "hibernate.boot.allow_jdbc_metadata_access", false,
                "hibernate.hbm2ddl.auto", "none",
                "jakarta.persistence.schema-generation.database.action", "none",
                "hibernate.default_schema", "pawbridge_animal",
                "hibernate.jdbc.time_zone", "UTC",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        return factory;
    }

    @Bean
    JpaTransactionManager animalCommandJpaTransactionManager(
            @Qualifier("animalCommandEntityManagerFactory") EntityManagerFactory factory,
            @Qualifier("writeDataSource") DataSource source) {
        var manager = new JpaTransactionManager(factory);
        manager.setDataSource(source);
        manager.setDefaultTimeout(15);
        return manager;
    }

    @Bean
    EntityManager animalCommandEntityManager(
            @Qualifier("animalCommandEntityManagerFactory") EntityManagerFactory factory) {
        return SharedEntityManagerCreator.createSharedEntityManager(factory);
    }

    @Bean
    JpaRepositoryFactory animalCommandRepositoryFactory(@Qualifier("animalCommandEntityManager") EntityManager entityManager) {
        return new JpaRepositoryFactory(entityManager);
    }

    @Bean
    AnimalRepository animalCommandAnimals(@Qualifier("animalCommandRepositoryFactory") JpaRepositoryFactory repositories) {
        return repositories.getRepository(AnimalRepository.class);
    }

    @Bean
    ShelterRepository animalCommandShelters(@Qualifier("animalCommandRepositoryFactory") JpaRepositoryFactory repositories) {
        return repositories.getRepository(ShelterRepository.class);
    }

    @Bean
    OutboxEventRepository animalCommandOutboxRepository(@Qualifier("animalCommandRepositoryFactory") JpaRepositoryFactory repositories) {
        return repositories.getRepository(OutboxEventRepository.class);
    }

    @Bean
    AnimalCommandActors animalCommandActors(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeDataSource") DataSource source) {
        if (jdbc.getDataSource() != source) throw new IllegalArgumentException("Animal actors must use the same writer DataSource");
        return new JdbcAnimalCommandActors(jdbc);
    }

    @Bean
    SearchDocumentWriter animalCommandSearch(@Qualifier("writeDataSource") DataSource source, KoreanSearchAnalyzer analyzer) {
        return new SearchDocumentWriter(source, analyzer);
    }

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
