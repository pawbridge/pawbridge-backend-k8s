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
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    fun `두 writer가 모두 꺼져 있을 때__입력 없이 구성하면__writer와 감사와 경로를 만들지 않는다`(
        flag: String,
    ) {
        // given
        var selected = runner()
        if (flag != "absent") {
            selected = selected.withPropertyValues("pawbridge.shelter-commands.enabled=$flag")
        }

        // when
        selected.run { context ->
            // then
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
    fun `보호소 writer와 선택적 동물 명령이 주어졌을 때__구성하면__제한된 writer 하나를 공유하고 합류 격리를 검증한다`(
        animals: Boolean,
    ) {
        // given
        val source = mockk<DataSource>()
        val owned = AtomicReference<EntityManagerFactory>()
        val analyzer = AtomicReference<KoreanSearchAnalyzer>()

        // when
        writer(source)
            .withPropertyValues("pawbridge.shelter-commands.enabled=TRUE", "pawbridge.animal-commands.enabled=$animals")
            .run { context ->
                // then
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
                verify(exactly = 0) { source.connection }
            }
        assertThat(owned.get()).isNotNull()
        assertThat(owned.get().isOpen).isFalse()
        assertThat(analyzer.get()).isNotNull()
        verify(exactly = 0) { source.connection }
    }

    @Test
    fun `사용자 조회 소스가 다를 때__보호소 쓰기를 구성하면__데이터베이스 접근 전에 거부한다`() {
        // given
        val source = mockk<DataSource>()
        val other = mockk<DataSource>()

        // when
        runner().withPropertyValues("pawbridge.shelter-commands.enabled=true")
            .withBean("writeDataSource", DataSource::class.java, Supplier { source })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(other) })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .run { context ->
                // then
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseInstanceOf(IllegalArgumentException::class.java)
            }
        verify(exactly = 0) { source.connection }
        verify(exactly = 0) { other.connection }
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
