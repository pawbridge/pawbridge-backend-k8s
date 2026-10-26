package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.travel.application.PetTravel
import com.pawbridge.backend.animal.internal.travel.web.PetTravelController
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.justRun
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
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
    fun `기능이 기본 꺼짐이거나 별칭일 때__조립하면__카탈로그를 만들지 않고 조회 입력도 요구하지 않는다`(flag: String) {
        // given
        var context = runner()
        if (flag != "absent") {
            context = context.withPropertyValues("pawbridge.animal-travel.enabled=$flag")
        }

        // when
        context.run { app ->
            // then
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
    fun `기능이 켜졌지만 백엔드가 PostgreSQL이 아닐 때__조립하면__조회 접근 전에 명시 선택을 요구하며 실패한다`(backend: String) {
        // given
        var context = runner().withPropertyValues("pawbridge.animal-travel.enabled=true")
        if (backend != "absent") {
            context = context.withPropertyValues("pawbridge.animal-query.backend=$backend")
        }

        // when
        context.run { app ->
            // then
            assertThat(app).hasFailed()
            assertThat(app.startupFailure)
                .hasRootCauseMessage("Travel catalog requires explicit PostgreSQL selection")
        }
    }

    @Test
    fun `기존 조회 자원이 있을 때__카탈로그를 조립하면__새 풀이나 모델 없이 목록에만 반복 읽기 격리를 쓴다`() {
        // given
        val source: DataSource = mockk<DataSource>()
        val manager: JdbcTransactionManager = mockk<JdbcTransactionManager>()
        val jdbc = JdbcTemplate(source)
        jdbc.queryTimeout = 3
        val reads = TransactionTemplate(manager)
        reads.isReadOnly = true
        reads.timeout = 3

        excludeRecords { source.hashCode() }
        justRun { manager.afterPropertiesSet() }

        // when
        runner().withBean("dataSource", DataSource::class.java, Supplier { source })
            .withBean("transactionManager", JdbcTransactionManager::class.java, Supplier { manager })
            .withBean("readJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            .withBean("readOnlyTransaction", TransactionTemplate::class.java, Supplier { reads })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .withPropertyValues("pawbridge.animal-travel.enabled=TrUe", "pawbridge.animal-query.backend=postgresql")
            .run { app ->
                // then
                assertThat(app).hasNotFailed()
                    .hasSingleBean(PetTravel::class.java)
                    .hasSingleBean(PetTravelController::class.java)
                    .hasSingleBean(DataSource::class.java)
                    .hasSingleBean(JdbcTransactionManager::class.java)
                    .doesNotHaveBean(EntityManagerFactory::class.java)
                    .doesNotHaveBean("writeDataSource")
                val list = app.getBean("animalTravelListReadOnlyTransaction", TransactionTemplate::class.java)
                assertThat(list.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ)
                assertThat(list.isReadOnly).isTrue()
                assertThat(list.timeout).isEqualTo(3)
                assertThat(reads.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
                assertThat(reads.timeout).isEqualTo(3)
                assertThat(reads.isReadOnly).isTrue()
                confirmVerified(source)
                verify(exactly = 0) { manager.getTransaction(any()) }
            }
    }
}
