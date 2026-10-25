package com.pawbridge.backend

import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics
import com.pawbridge.backend.animal.internal.statistics.web.AdminAnimalStatisticsController
import com.pawbridge.backend.animal.internal.statistics.web.AnimalStatisticsController
import com.pawbridge.backend.animal.internal.statistics.web.AnimalStatisticsExceptionHandler
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration
import com.pawbridge.backend.boot.configuration.AnimalStatisticsConfiguration
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
import java.time.Clock
import java.time.ZoneId
import java.util.function.Supplier
import javax.sql.DataSource

class AnimalStatisticsConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun `통계가 꺼졌을 때__읽기 입력 없이 조립하면__통계 빈을 등록하지 않는다`(
        flag: String,
    ) {
        // given
        var runner = context()
        if (flag == "false") {
            runner = runner.withPropertyValues("pawbridge.animal-statistics.enabled=false")
        }

        // when
        runner.run { result ->
            // then
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
    fun `통계가 켜지고 읽기와 기본 쓰기 입력이 있을 때__조립하고 닫으면__읽기 트랜잭션과 서울 시계를 유지한다`() {
        // given
        val source = mockk<DataSource>()
        val writer = mockk<DataSource>()
        val manager = mockk<PlatformTransactionManager>()
        val writerManager = mockk<PlatformTransactionManager>()
        val runner = context().withPropertyValues("pawbridge.animal-statistics.enabled=true")
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

        // when
        runner.run { result ->
            // then
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
                .withUserConfiguration(AnimalStatisticsConfiguration::class.java)
        }
    }
}
