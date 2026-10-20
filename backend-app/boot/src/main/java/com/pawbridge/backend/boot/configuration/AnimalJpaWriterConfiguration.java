package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.animal.api.AnimalCommandActors;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.OutboxEventRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter;
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import com.pawbridge.backend.user.internal.animalcommand.infrastructure.JdbcAnimalCommandActors;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Shared, explicitly imported Animal writer; no component scan, new pool or schema generation. */
@Configuration(proxyBeanMethods = false)
@Import({AnimalSearchConfiguration.class, JpaAuditingConfiguration.class})
public class AnimalJpaWriterConfiguration {
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
        manager.setValidateExistingTransaction(true);
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

}
