package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import com.pawbridge.backend.animal.internal.shelter.web.ShelterDiscoveryReadController
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadController
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadExceptionHandler
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.function.Supplier
import javax.sql.DataSource

class AnimalShelterConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun `보호소가 꺼졌고 postgresql 백엔드일 때__DB 입력 없이 조립하면__보호소 빈을 만들지 않는다`(
        flag: String,
    ) {
        // given
        var runner = context().withPropertyValues("pawbridge.animal-query.backend=postgresql")
        if (flag == "false") {
            runner = runner.withPropertyValues("pawbridge.animal-shelters.enabled=false")
        }

        // when
        runner.run { result ->
            // then
            assertThat(result).hasNotFailed()
                .doesNotHaveBean(ShelterReads::class.java)
                .doesNotHaveBean(ShelterReadController::class.java)
                .doesNotHaveBean(ShelterDiscoveryReadController::class.java)
                .doesNotHaveBean(ShelterReadExceptionHandler::class.java)
                .doesNotHaveBean(DataSource::class.java)
                .doesNotHaveBean(PlatformTransactionManager::class.java)
                .doesNotHaveBean(KoreanSearchAnalyzer::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "elasticsearch", "postgresql", "POSTGRESQL"])
    fun `보호소가 켜지고 백엔드를 골랐을 때__조립하고 닫으면__읽기 입력을 재사용하고 해당 컨트롤러만 등록한다`(
        backend: String,
    ) {
        // given
        val source = mockk<DataSource>()
        val writer = mockk<DataSource>()
        val manager = mockk<PlatformTransactionManager>()
        val writerManager = mockk<PlatformTransactionManager>()
        val mapper = ObjectMapper()
        var runner = context().withPropertyValues("pawbridge.animal-shelters.enabled=true")
            .withBean("dataSource", DataSource::class.java, Supplier { source })
            .withBean(
                "writerDataSource",
                DataSource::class.java,
                Supplier { writer },
                BeanDefinitionCustomizer { definition -> definition.setPrimary(true) },
            )
            .withBean("transactionManager", PlatformTransactionManager::class.java, Supplier { manager })
            .withBean(
                "writerTransactionManager",
                PlatformTransactionManager::class.java,
                Supplier { writerManager },
                BeanDefinitionCustomizer { definition -> definition.setPrimary(true) },
            )
            .withBean(ObjectMapper::class.java, Supplier { mapper })
        if (backend != "absent") {
            runner = runner.withPropertyValues("pawbridge.animal-query.backend=$backend")
        }

        // when
        runner.run { result ->
            // then
            assertThat(result).hasNotFailed()
                .hasSingleBean(ShelterReads::class.java)
                .hasSingleBean(ShelterReadController::class.java)
                .hasSingleBean(ShelterReadExceptionHandler::class.java)
                .doesNotHaveBean(KoreanSearchAnalyzer::class.java)
            assertThat(result.getBeansOfType(ShelterDiscoveryReadController::class.java))
                .hasSize(if ("postgresql".equals(backend, ignoreCase = true)) 1 else 0)
            assertThat(result.getBeansOfType(DataSource::class.java)).containsOnlyKeys("dataSource", "writerDataSource")
            val reads = result.getBean(ShelterReads::class.java)
            assertThat(ReflectionTestUtils.getField(reads, "mapper")).isSameAs(mapper)
            for (field in arrayOf("jdbc", "discoveryJdbc")) {
                val jdbc = (ReflectionTestUtils.getField(reads, field) as NamedParameterJdbcTemplate).jdbcTemplate as JdbcTemplate
                assertThat(jdbc.dataSource).isSameAs(source)
                assertThat(jdbc.queryTimeout).isEqualTo(if (field == "discoveryJdbc") 5 else -1)
            }
            for (field in arrayOf("reads", "discoveryReads")) {
                val transaction = ReflectionTestUtils.getField(reads, field) as TransactionTemplate
                assertThat(transaction.transactionManager).isSameAs(manager)
                assertThat(transaction.isReadOnly).isTrue()
                assertThat(transaction.isolationLevel).isEqualTo(
                    if (field == "discoveryReads") {
                        TransactionDefinition.ISOLATION_REPEATABLE_READ
                    } else {
                        TransactionDefinition.ISOLATION_DEFAULT
                    },
                )
                assertThat(transaction.timeout).isEqualTo(
                    if (field == "discoveryReads") 5 else TransactionDefinition.TIMEOUT_DEFAULT,
                )
            }
            assertThat(AopUtils.isAopProxy(reads)).isFalse()
            assertThat(AopUtils.isAopProxy(result.getBean(ShelterReadController::class.java))).isFalse()
            assertThat(PawBridgeApplication::class.java.getAnnotation(Import::class.java).value.map { it.java })
                .contains(AnimalShelterConfiguration::class.java)
        }
        confirmNoBusinessCalls(source, writer, manager, writerManager)
    }

    companion object {
        /** Spring hashes registered beans, so ignore those calls and fail on any other call to the mocks. */
        private fun confirmNoBusinessCalls(vararg mocks: Any) {
            for (mock in mocks) {
                excludeRecords {
                    mock.hashCode()
                    mock.toString()
                    mock.equals(any())
                }
            }
            confirmVerified(*mocks)
        }

        private fun context(): ApplicationContextRunner {
            return ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
                .withUserConfiguration(AnimalShelterConfiguration::class.java)
        }
    }
}
