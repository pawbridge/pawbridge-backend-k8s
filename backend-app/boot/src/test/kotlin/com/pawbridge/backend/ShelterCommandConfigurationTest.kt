package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.api.AnimalShelterMembers
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommands
import com.pawbridge.backend.animal.internal.sheltercommand.web.ShelterCommandController
import com.pawbridge.backend.boot.configuration.AnimalCommandConfiguration
import com.pawbridge.backend.boot.configuration.ShelterCommandConfiguration
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.auditing.AuditingHandler
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.sql.DataSource

class ShelterCommandConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "on", "yes", "1", "unknown"])
    fun given__neither_writer_enabled__when__assembling_without_inputs__then__create_no_writer_or_auditing_or_routes(
        flag: String,
    ) {
        var selected = runner()
        if (flag != "absent") {
            selected = selected.withPropertyValues("pawbridge.shelter-commands.enabled=$flag")
        }
        selected.run { context ->
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(EntityManagerFactory::class.java)
                .doesNotHaveBean(JpaTransactionManager::class.java)
                .doesNotHaveBean(ShelterCommands::class.java)
                .doesNotHaveBean(AnimalCommandService::class.java)
                .doesNotHaveBean(KoreanSearchAnalyzer::class.java)
                .doesNotHaveBean("jpaAuditingHandler")
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__shelter_writer_and_optional_animal_commands__when__assembling__then__share_one_bounded_writer_and_validate_joined_isolation(
        animals: Boolean,
    ) {
        val source = mock(DataSource::class.java)
        val owned = AtomicReference<EntityManagerFactory>()
        val analyzer = AtomicReference<KoreanSearchAnalyzer>()
        writer(source)
            .withPropertyValues("pawbridge.shelter-commands.enabled=TRUE", "pawbridge.animal-commands.enabled=$animals")
            .run { context ->
                assertThat(context).hasNotFailed()
                    .hasSingleBean(ShelterCommands::class.java)
                    .hasSingleBean(ShelterCommandController::class.java)
                assertThat(context.getBeansOfType(EntityManagerFactory::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(JpaTransactionManager::class.java)).hasSize(1)
                val factory = context.getBean("animalCommandEntityManagerFactory", EntityManagerFactory::class.java)
                owned.set(factory)
                val manager = context.getBean("animalCommandJpaTransactionManager", JpaTransactionManager::class.java)
                assertThat(manager.dataSource).isSameAs(source)
                assertThat(manager.entityManagerFactory).isSameAs(factory)
                assertThat(manager.defaultTimeout).isEqualTo(15)
                assertThat(manager.isValidateExistingTransaction).isTrue()
                val writes = ReflectionTestUtils.getField(context.getBean(ShelterCommands::class.java), "writes") as TransactionTemplate
                assertThat(writes.transactionManager).isSameAs(manager)
                assertThat(writes.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED)
                assertThat(writes.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
                assertThat(writes.isReadOnly).isFalse()
                assertThat(context.getBean(SearchDocumentWriter::class.java).dataSource()).isSameAs(source)
                assertThat(
                    (ReflectionTestUtils.getField(context.getBean(AnimalShelterMembers::class.java), "jdbc") as JdbcTemplate)
                        .dataSource,
                ).isSameAs(source)
                assertThat(context.getBeansOfType(AnimalCommandService::class.java)).hasSize(if (animals) 1 else 0)
                assertThat(context.getBeansOfType(KoreanSearchAnalyzer::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(AuditingHandler::class.java)).hasSize(1)
                analyzer.set(context.getBean(KoreanSearchAnalyzer::class.java))
                verify(source, never()).getConnection()
            }
        assertThat(owned.get()).isNotNull()
        assertThat(owned.get().isOpen).isFalse()
        assertThat(analyzer.get()).isNotNull()
        verify(source, never()).getConnection()
    }

    @Test
    fun given__different_user_lookup_source__when__assembling_shelter_writes__then__reject_before_any_database_access() {
        val source = mock(DataSource::class.java)
        val other = mock(DataSource::class.java)
        runner().withPropertyValues("pawbridge.shelter-commands.enabled=true")
            .withBean("writeDataSource", DataSource::class.java, Supplier { source })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(other) })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseInstanceOf(IllegalArgumentException::class.java)
            }
        verify(source, never()).getConnection()
        verify(other, never()).getConnection()
    }

    companion object {
        private fun runner(): ApplicationContextRunner {
            return ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
                .withUserConfiguration(AnimalCommandConfiguration::class.java, ShelterCommandConfiguration::class.java)
        }

        private fun writer(source: DataSource): ApplicationContextRunner {
            return runner()
                .withBean("writeDataSource", DataSource::class.java, Supplier { source })
                .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(source) })
                .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
        }
    }
}
