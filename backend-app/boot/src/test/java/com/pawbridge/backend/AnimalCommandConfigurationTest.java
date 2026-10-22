package com.pawbridge.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.api.AnimalCommandActors;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.BaseTimeEntity;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter;
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService;
import com.pawbridge.backend.animal.internal.command.web.AnimalCommandController;
import com.pawbridge.backend.animal.internal.command.web.AnimalCommandExceptionHandler;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery;
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryController;
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations;
import com.pawbridge.backend.boot.configuration.AnimalCommandConfiguration;
import com.pawbridge.backend.boot.configuration.AnimalDiscoveryConfiguration;
import com.pawbridge.backend.boot.configuration.AnimalRecommendationsConfiguration;
import com.pawbridge.backend.boot.configuration.AnimalSearchConfiguration;
import jakarta.persistence.EntityManagerFactory;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnimalCommandConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "yes", "on", "1", "unknown"})
    void given__commands_not_explicitly_true__when__assembling_without_database_inputs__then__create_no_command_or_search_or_auditing_beans(String flag) {
        var runner = runner();
        if (!flag.equals("absent")) runner = runner.withPropertyValues("pawbridge.animal-commands.enabled=" + flag);
        runner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(EntityManagerFactory.class).doesNotHaveBean(JpaTransactionManager.class)
                .doesNotHaveBean(AnimalCommandService.class).doesNotHaveBean(AnimalCommandActors.class)
                .doesNotHaveBean(AnimalCommandController.class).doesNotHaveBean(AnimalCommandExceptionHandler.class)
                .doesNotHaveBean(KoreanSearchAnalyzer.class).doesNotHaveBean("jpaAuditingHandler"));
    }

    @Test
    void given__commands_enabled__when__assembling__then__use_one_writer_unit_selected_repositories_and_bounded_service_without_database_access() {
        DataSource writer = mock(DataSource.class);
        DataSource reader = mock(DataSource.class);
        AtomicReference<KoreanSearchAnalyzer> owned = new AtomicReference<>();
        AtomicReference<EntityManagerFactory> unit = new AtomicReference<>();
        writerInputs(writer).withBean("dataSource", DataSource.class, () -> reader)
                .withPropertyValues("pawbridge.animal-commands.enabled=TRUE").run(context -> {
                    assertThat(context).hasNotFailed();
                    var factory = context.getBean("&animalCommandEntityManagerFactory", LocalContainerEntityManagerFactoryBean.class);
                    EntityManagerFactory emf = context.getBean("animalCommandEntityManagerFactory", EntityManagerFactory.class);
                    unit.set(emf);
                    assertThat(factory.getDataSource()).isSameAs(writer);
                    assertThat(factory.getPersistenceUnitInfo().getManagedClassNames()).containsExactlyInAnyOrder(
                            Animal.class.getName(), Shelter.class.getName(), BaseTimeEntity.class.getName());
                    assertThat(factory.getJpaPropertyMap()).containsEntry("hibernate.boot.allow_jdbc_metadata_access", false)
                            .containsEntry("hibernate.hbm2ddl.auto", "none")
                            .containsEntry("jakarta.persistence.schema-generation.database.action", "none")
                            .containsEntry("hibernate.default_schema", "pawbridge_animal")
                            .containsEntry("hibernate.jdbc.time_zone", "UTC")
                            .containsEntry("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
                    assertThat(context.getBeansOfType(EntityManagerFactory.class)).hasSize(1);
                    assertThat(context.getBeansOfType(AnimalRepository.class)).hasSize(1);
                    assertThat(context.getBeansOfType(ShelterRepository.class)).hasSize(1);
                    var manager = context.getBean("animalCommandJpaTransactionManager", JpaTransactionManager.class);
                    assertThat(manager.getDataSource()).isSameAs(writer);
                    assertThat(manager.getEntityManagerFactory()).isSameAs(emf);
                    assertThat(manager.getDefaultTimeout()).isEqualTo(15);
                    var service = context.getBean(AnimalCommandService.class);
                    var writes = (TransactionTemplate) ReflectionTestUtils.getField(service, "writes");
                    assertThat(writes.getTransactionManager()).isSameAs(manager);
                    assertThat(writes.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
                    assertThat(writes.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
                    assertThat(writes.getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
                    assertThat(writes.isReadOnly()).isFalse();
                    var actorJdbc = (JdbcTemplate) ReflectionTestUtils.getField(context.getBean(AnimalCommandActors.class), "jdbc");
                    assertThat(actorJdbc.getDataSource()).isSameAs(writer);
                    assertThat(context.getBean(SearchDocumentWriter.class).dataSource()).isSameAs(writer);
                    owned.set(context.getBean(KoreanSearchAnalyzer.class));
                    assertThat(ReflectionTestUtils.getField(context.getBean(SearchDocumentWriter.class), "analyzer")).isSameAs(owned.get());
                    assertThat(context).hasSingleBean(AnimalCommandController.class).hasSingleBean(AnimalCommandExceptionHandler.class)
                            .doesNotHaveBean(AnimalDiscovery.class).doesNotHaveBean(AnimalDiscoveryController.class)
                            .doesNotHaveBean(AnimalRecommendations.class).hasBean("jpaAuditingHandler");
                    assertThat(context.getBeansOfType(KoreanSearchAnalyzer.class)).hasSize(1);
                    verifyNoInteractions(writer, reader);
                });
        assertThat(unit.get().isOpen()).isFalse();
        assertThatThrownBy(() -> owned.get().tokens("말티즈")).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"discovery", "recommendations", "both"})
    void given__commands_and_reads_enabled__when__assembling__then__share_one_analyzer_and_keep_reader_and_writer_managers_separate(String mode) {
        DataSource writer = mock(DataSource.class);
        DataSource reader = mock(DataSource.class);
        PlatformTransactionManager readerManager = mock(PlatformTransactionManager.class);
        AtomicReference<KoreanSearchAnalyzer> owned = new AtomicReference<>();
        var runner = writerInputs(writer).withBean("dataSource", DataSource.class, () -> reader)
                .withBean("transactionManager", PlatformTransactionManager.class, () -> readerManager)
                .withPropertyValues("pawbridge.animal-commands.enabled=true", "python-ai-service.url=https://fixture.example.invalid");
        if (!mode.equals("recommendations")) runner = runner.withPropertyValues("pawbridge.animal-discovery.enabled=true");
        if (!mode.equals("discovery")) runner = runner.withPropertyValues("pawbridge.animal-recommendations.enabled=true");
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(KoreanSearchAnalyzer.class)).hasSize(1);
            owned.set(context.getBean(KoreanSearchAnalyzer.class));
            AnimalDiscovery discovery = context.getBean(AnimalDiscovery.class);
            assertThat(ReflectionTestUtils.getField(discovery, "analyzer")).isSameAs(owned.get());
            assertThat(ReflectionTestUtils.getField(context.getBean(SearchDocumentWriter.class), "analyzer")).isSameAs(owned.get());
            var reads = (TransactionTemplate) ReflectionTestUtils.getField(discovery, "transactions");
            assertThat(reads.getTransactionManager()).isSameAs(readerManager);
            assertThat(reads.isReadOnly()).isTrue();
            assertThat(context.getBean("animalCommandJpaTransactionManager", JpaTransactionManager.class).getDataSource()).isSameAs(writer);
            assertThat(context.containsBean("animalDiscoveryController")).isEqualTo(!mode.equals("recommendations"));
            verifyNoInteractions(writer, reader, readerManager);
        });
        assertThatThrownBy(() -> owned.get().tokens("말티즈")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void given__actor_jdbc_uses_another_source__when__commands_enabled__then__fail_before_opening_a_database_connection() {
        DataSource writer = mock(DataSource.class);
        DataSource other = mock(DataSource.class);
        runner().withBean("writeDataSource", DataSource.class, () -> writer)
                .withBean("writeJdbc", JdbcTemplate.class, () -> new JdbcTemplate(other))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("pawbridge.animal-commands.enabled=true").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("Animal actors must use the same writer DataSource");
                    verifyNoInteractions(writer, other);
                });
    }

    @Test
    void given__only_reader_inputs__when__commands_enabled__then__fail_instead_of_using_the_reader() {
        DataSource reader = mock(DataSource.class);
        runner().withBean("dataSource", DataSource.class, () -> reader)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("pawbridge.animal-commands.enabled=true").run(context -> {
                    assertThat(context).hasFailed();
                    verifyNoInteractions(reader);
                });
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(AnimalCommandConfiguration.class, AnimalDiscoveryConfiguration.class,
                        AnimalRecommendationsConfiguration.class, AnimalSearchConfiguration.class);
    }

    private static ApplicationContextRunner writerInputs(DataSource writer) {
        return runner().withBean("writeDataSource", DataSource.class, () -> writer)
                .withBean("writeJdbc", JdbcTemplate.class, () -> new JdbcTemplate(writer))
                .withBean(ObjectMapper.class, ObjectMapper::new);
    }
}
