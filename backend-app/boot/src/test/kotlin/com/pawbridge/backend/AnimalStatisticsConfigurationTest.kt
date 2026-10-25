package com.pawbridge.backend

import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics
import com.pawbridge.backend.animal.internal.statistics.web.AdminAnimalStatisticsController
import com.pawbridge.backend.animal.internal.statistics.web.AnimalStatisticsController
import com.pawbridge.backend.animal.internal.statistics.web.AnimalStatisticsExceptionHandler
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration
import com.pawbridge.backend.boot.configuration.AnimalStatisticsConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
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
import java.time.Clock
import java.time.ZoneId
import java.util.function.Supplier
import javax.sql.DataSource

class AnimalStatisticsConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun given__statistics_not_enabled__when__assembling_without_reader_inputs__then__register_no_statistics_beans(
        flag: String,
    ) {
        var runner = context()
        if (flag == "false") {
            runner = runner.withPropertyValues("pawbridge.animal-statistics.enabled=false")
        }
        runner.run { result ->
            assertThat(result).hasNotFailed()
                .doesNotHaveBean(AnimalStatistics::class.java)
                .doesNotHaveBean(AnimalStatisticsController::class.java)
                .doesNotHaveBean(AdminAnimalStatisticsController::class.java)
                .doesNotHaveBean(AnimalStatisticsExceptionHandler::class.java)
                .doesNotHaveBean(DataSource::class.java)
                .doesNotHaveBean(PlatformTransactionManager::class.java)
        }
    }

    @Test
    fun given__statistics_enabled_with_named_reader_and_primary_writer__when__assembling_and_closing__then__keep_reader_transaction_and_seoul_clock_without_outer_transaction() {
        val source = mock(DataSource::class.java)
        val writer = mock(DataSource::class.java)
        val manager = mock(PlatformTransactionManager::class.java)
        val writerManager = mock(PlatformTransactionManager::class.java)
        context().withPropertyValues("pawbridge.animal-statistics.enabled=true")
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
            .run { result ->
                assertThat(result).hasNotFailed()
                    .hasSingleBean(AnimalStatistics::class.java)
                    .hasSingleBean(AnimalStatisticsController::class.java)
                    .hasSingleBean(AdminAnimalStatisticsController::class.java)
                    .hasSingleBean(AnimalStatisticsExceptionHandler::class.java)
                val statistics = result.getBean(AnimalStatistics::class.java)
                val jdbc = (ReflectionTestUtils.getField(statistics, "jdbc") as NamedParameterJdbcTemplate).jdbcTemplate as JdbcTemplate
                assertThat(jdbc.dataSource).isSameAs(source)
                assertThat(jdbc.queryTimeout).isEqualTo(-1)
                val reads = ReflectionTestUtils.getField(statistics, "reads") as TransactionTemplate
                assertThat(reads.transactionManager).isSameAs(manager)
                assertThat(reads.isReadOnly).isTrue()
                assertThat(reads.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
                assertThat(reads.timeout).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT)
                assertThat((ReflectionTestUtils.getField(statistics, "clock") as Clock).zone).isEqualTo(ZoneId.of("Asia/Seoul"))
                assertThat(AopUtils.isAopProxy(statistics)).isFalse()
                assertThat(AopUtils.isAopProxy(result.getBean(AdminAnimalStatisticsController::class.java))).isFalse()
                assertThat(PawBridgeApplication::class.java.getAnnotation(Import::class.java).value.map { it.java })
                    .contains(AnimalShelterConfiguration::class.java, AnimalStatisticsConfiguration::class.java)
            }
        verifyNoInteractions(source, writer, manager, writerManager)
    }

    companion object {
        private fun context(): ApplicationContextRunner {
            return ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
                .withUserConfiguration(AnimalStatisticsConfiguration::class.java)
        }
    }
}
