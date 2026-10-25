package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.BaseTimeEntity
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService
import com.pawbridge.backend.animal.internal.command.web.AnimalCommandController
import com.pawbridge.backend.animal.internal.command.web.AnimalCommandExceptionHandler
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryController
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations
import com.pawbridge.backend.boot.configuration.AnimalCommandConfiguration
import com.pawbridge.backend.boot.configuration.AnimalDiscoveryConfiguration
import com.pawbridge.backend.boot.configuration.AnimalRecommendationsConfiguration
import com.pawbridge.backend.boot.configuration.AnimalSearchConfiguration
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.sql.DataSource

class AnimalCommandConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes", "on", "1", "unknown"])
    fun given__commands_not_explicitly_true__when__assembling_without_database_inputs__then__create_no_command_or_search_or_auditing_beans(
        flag: String,
    ) {
        var runner = runner()
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.animal-commands.enabled=$flag")
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(EntityManagerFactory::class.java)
                .doesNotHaveBean(JpaTransactionManager::class.java)
                .doesNotHaveBean(AnimalCommandService::class.java)
                .doesNotHaveBean(AnimalCommandActors::class.java)
                .doesNotHaveBean(AnimalCommandController::class.java)
                .doesNotHaveBean(AnimalCommandExceptionHandler::class.java)
                .doesNotHaveBean(KoreanSearchAnalyzer::class.java)
                .doesNotHaveBean("jpaAuditingHandler")
        }
    }

    @Test
    fun given__commands_enabled__when__assembling__then__use_one_writer_unit_selected_repositories_and_bounded_service_without_database_access() {
        val writer = mock(DataSource::class.java)
        val reader = mock(DataSource::class.java)
        val owned = AtomicReference<KoreanSearchAnalyzer>()
        val unit = AtomicReference<EntityManagerFactory>()
        writerInputs(writer)
            .withBean("dataSource", DataSource::class.java, Supplier { reader })
            .withPropertyValues("pawbridge.animal-commands.enabled=TRUE")
            .run { context ->
                assertThat(context).hasNotFailed()
                val factory = context.getBean(
                    "&animalCommandEntityManagerFactory",
                    LocalContainerEntityManagerFactoryBean::class.java,
                )
                val emf = context.getBean("animalCommandEntityManagerFactory", EntityManagerFactory::class.java)
                unit.set(emf)
                assertThat(factory.dataSource).isSameAs(writer)
                assertThat(factory.persistenceUnitInfo!!.managedClassNames).containsExactlyInAnyOrder(
                    Animal::class.java.name,
                    Shelter::class.java.name,
                    BaseTimeEntity::class.java.name,
                )
                assertThat(factory.jpaPropertyMap)
                    .containsEntry("hibernate.boot.allow_jdbc_metadata_access", false)
                    .containsEntry("hibernate.hbm2ddl.auto", "none")
                    .containsEntry("jakarta.persistence.schema-generation.database.action", "none")
                    .containsEntry("hibernate.default_schema", "pawbridge_animal")
                    .containsEntry("hibernate.jdbc.time_zone", "UTC")
                    .containsEntry(
                        "hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                    )
                assertThat(context.getBeansOfType(EntityManagerFactory::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(AnimalRepository::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(ShelterRepository::class.java)).hasSize(1)
                val manager = context.getBean("animalCommandJpaTransactionManager", JpaTransactionManager::class.java)
                assertThat(manager.dataSource).isSameAs(writer)
                assertThat(manager.entityManagerFactory).isSameAs(emf)
                assertThat(manager.defaultTimeout).isEqualTo(15)
                val service = context.getBean(AnimalCommandService::class.java)
                val writes = ReflectionTestUtils.getField(service, "writes") as TransactionTemplate
                assertThat(writes.transactionManager).isSameAs(manager)
                assertThat(writes.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
                assertThat(writes.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED)
                assertThat(writes.timeout).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT)
                assertThat(writes.isReadOnly).isFalse()
                val actorJdbc = ReflectionTestUtils.getField(context.getBean(AnimalCommandActors::class.java), "jdbc") as JdbcTemplate
                assertThat(actorJdbc.dataSource).isSameAs(writer)
                assertThat(context.getBean(SearchDocumentWriter::class.java).dataSource()).isSameAs(writer)
                owned.set(context.getBean(KoreanSearchAnalyzer::class.java))
                assertThat(ReflectionTestUtils.getField(context.getBean(SearchDocumentWriter::class.java), "analyzer"))
                    .isSameAs(owned.get())
                assertThat(context)
                    .hasSingleBean(AnimalCommandController::class.java)
                    .hasSingleBean(AnimalCommandExceptionHandler::class.java)
                    .doesNotHaveBean(AnimalDiscovery::class.java)
                    .doesNotHaveBean(AnimalDiscoveryController::class.java)
                    .doesNotHaveBean(AnimalRecommendations::class.java)
                    .hasBean("jpaAuditingHandler")
                assertThat(context.getBeansOfType(KoreanSearchAnalyzer::class.java)).hasSize(1)
                verifyNoInteractions(writer, reader)
            }
        assertThat(unit.get().isOpen).isFalse()
        assertThatThrownBy { owned.get().tokens("말티즈") }.isInstanceOf(IllegalStateException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["discovery", "recommendations", "both"])
    fun given__commands_and_reads_enabled__when__assembling__then__share_one_analyzer_and_keep_reader_and_writer_managers_separate(
        mode: String,
    ) {
        val writer = mock(DataSource::class.java)
        val reader = mock(DataSource::class.java)
        val readerManager = mock(PlatformTransactionManager::class.java)
        val owned = AtomicReference<KoreanSearchAnalyzer>()
        var runner = writerInputs(writer)
            .withBean("dataSource", DataSource::class.java, Supplier { reader })
            .withBean("transactionManager", PlatformTransactionManager::class.java, Supplier { readerManager })
            .withPropertyValues(
                "pawbridge.animal-commands.enabled=true",
                "python-ai-service.url=https://fixture.example.invalid",
            )
        if (mode != "recommendations") {
            runner = runner.withPropertyValues("pawbridge.animal-discovery.enabled=true")
        }
        if (mode != "discovery") {
            runner = runner.withPropertyValues("pawbridge.animal-recommendations.enabled=true")
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(KoreanSearchAnalyzer::class.java)).hasSize(1)
            owned.set(context.getBean(KoreanSearchAnalyzer::class.java))
            val discovery = context.getBean(AnimalDiscovery::class.java)
            assertThat(ReflectionTestUtils.getField(discovery, "analyzer")).isSameAs(owned.get())
            assertThat(ReflectionTestUtils.getField(context.getBean(SearchDocumentWriter::class.java), "analyzer"))
                .isSameAs(owned.get())
            val reads = ReflectionTestUtils.getField(discovery, "transactions") as TransactionTemplate
            assertThat(reads.transactionManager).isSameAs(readerManager)
            assertThat(reads.isReadOnly).isTrue()
            assertThat(
                context.getBean("animalCommandJpaTransactionManager", JpaTransactionManager::class.java).dataSource,
            ).isSameAs(writer)
            assertThat(context.containsBean("animalDiscoveryController")).isEqualTo(mode != "recommendations")
            verifyNoInteractions(writer, reader, readerManager)
        }
        assertThatThrownBy { owned.get().tokens("말티즈") }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun given__actor_jdbc_uses_another_source__when__commands_enabled__then__fail_before_opening_a_database_connection() {
        val writer = mock(DataSource::class.java)
        val other = mock(DataSource::class.java)
        runner()
            .withBean("writeDataSource", DataSource::class.java, Supplier { writer })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(other) })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .withPropertyValues("pawbridge.animal-commands.enabled=true")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseMessage("Animal actors must use the same writer DataSource")
                verifyNoInteractions(writer, other)
            }
    }

    @Test
    fun given__only_reader_inputs__when__commands_enabled__then__fail_instead_of_using_the_reader() {
        val reader = mock(DataSource::class.java)
        runner()
            .withBean("dataSource", DataSource::class.java, Supplier { reader })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .withPropertyValues("pawbridge.animal-commands.enabled=true")
            .run { context ->
                assertThat(context).hasFailed()
                verifyNoInteractions(reader)
            }
    }

    companion object {
        private fun runner(): ApplicationContextRunner {
            return ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
                .withUserConfiguration(
                    AnimalCommandConfiguration::class.java,
                    AnimalDiscoveryConfiguration::class.java,
                    AnimalRecommendationsConfiguration::class.java,
                    AnimalSearchConfiguration::class.java,
                )
        }

        private fun writerInputs(writer: DataSource): ApplicationContextRunner {
            return runner()
                .withBean("writeDataSource", DataSource::class.java, Supplier { writer })
                .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(writer) })
                .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
        }
    }
}
