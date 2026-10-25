package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.travel.application.PetTravel
import com.pawbridge.backend.animal.internal.travel.web.PetTravelController
import com.pawbridge.backend.support.anyValue
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.function.Supplier
import javax.sql.DataSource

class AnimalTravelConfigurationTest {
    fun runner(): ApplicationContextRunner {
        return ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withUserConfiguration(AnimalTravelConfiguration::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "on", "yes", "1", "unknown"])
    fun feature_is_default_off_and_aliases_do_not_instantiate_any_catalog_or_require_reader_inputs(flag: String) {
        var context = runner()
        if (flag != "absent") {
            context = context.withPropertyValues("pawbridge.animal-travel.enabled=$flag")
        }
        context.run { app ->
            assertThat(app).hasNotFailed()
                .doesNotHaveBean(PetTravel::class.java)
                .doesNotHaveBean(PetTravelController::class.java)
                .doesNotHaveBean(TransactionTemplate::class.java)
                .doesNotHaveBean(DataSource::class.java)
                .doesNotHaveBean(EntityManagerFactory::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "elasticsearch", "mysql", "unknown"])
    fun enabled_feature_requires_explicit_postgresql_before_reader_access(backend: String) {
        var context = runner().withPropertyValues("pawbridge.animal-travel.enabled=true")
        if (backend != "absent") {
            context = context.withPropertyValues("pawbridge.animal-query.backend=$backend")
        }
        context.run { app ->
            assertThat(app).hasFailed()
            assertThat(app.startupFailure)
                .hasRootCauseMessage("Travel catalog requires explicit PostgreSQL selection")
        }
    }

    @Test
    fun catalog_reuses_existing_reader_and_copies_only_list_isolation_without_new_pools_or_models() {
        val source: DataSource = mock(DataSource::class.java)
        val manager: JdbcTransactionManager = mock(JdbcTransactionManager::class.java)
        val jdbc = JdbcTemplate(source)
        jdbc.queryTimeout = 3
        val reads = TransactionTemplate(manager)
        reads.isReadOnly = true
        reads.timeout = 3
        runner().withBean("dataSource", DataSource::class.java, Supplier { source })
            .withBean("transactionManager", JdbcTransactionManager::class.java, Supplier { manager })
            .withBean("readJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            .withBean("readOnlyTransaction", TransactionTemplate::class.java, Supplier { reads })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .withPropertyValues("pawbridge.animal-travel.enabled=TrUe", "pawbridge.animal-query.backend=postgresql")
            .run { app ->
                assertThat(app).hasNotFailed()
                    .hasSingleBean(PetTravel::class.java)
                    .hasSingleBean(PetTravelController::class.java)
                    .hasSingleBean(DataSource::class.java)
                    .hasSingleBean(JdbcTransactionManager::class.java)
                    .doesNotHaveBean(EntityManagerFactory::class.java)
                    .doesNotHaveBean("writeDataSource")
                val list = app.getBean("animalTravelListReadOnlyTransaction", TransactionTemplate::class.java)
                assertThat(list).isNotSameAs(reads)
                assertThat(list.transactionManager).isSameAs(manager)
                assertThat(list.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ)
                assertThat(list.isReadOnly).isTrue()
                assertThat(list.timeout).isEqualTo(3)
                assertThat(reads.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
                assertThat(reads.timeout).isEqualTo(3)
                assertThat(reads.isReadOnly).isTrue()
                val service: Any = app.getBean(PetTravel::class.java)
                assertThat(ReflectionTestUtils.getField(service, "reads")).isSameAs(reads)
                assertThat(ReflectionTestUtils.getField(service, "repeatableReads")).isSameAs(list)
                val repository: Any = ReflectionTestUtils.getField(service, "repository")!!
                assertThat(ReflectionTestUtils.getField(repository, "jdbc")).isSameAs(jdbc)
                verifyNoInteractions(source)
                verify(manager, never()).getTransaction(anyValue<TransactionDefinition>())
            }
    }
}
