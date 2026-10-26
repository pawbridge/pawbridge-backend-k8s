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
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
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
    fun `보호소가 켜지고 백엔드를 골랐을 때__조립하고 닫으면__백엔드에 맞는 컨트롤러만 등록하고 연결을 열지 않는다`(
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
