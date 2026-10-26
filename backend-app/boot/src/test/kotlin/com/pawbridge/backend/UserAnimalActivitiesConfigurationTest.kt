package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.api.AnimalMemberReads
import com.pawbridge.backend.boot.configuration.UserAnimalActivitiesConfiguration
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.justRun
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.util.function.Supplier
import javax.sql.DataSource

class UserAnimalActivitiesConfigurationTest {

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun `활성화 설정이 없거나 꺼졌을 때__조립하면__활동 빈을 만들지 않는다`(flag: String) {
        // given
        var runner = ApplicationContextRunner().withUserConfiguration(UserAnimalActivitiesConfiguration::class.java)
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.user-animal-activities.enabled=false")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(UserAnimalActivities::class.java)
            assertThat(context).doesNotHaveBean(AnimalMemberReads::class.java)
        }
    }

    @Test
    fun `기존 쓰기 자원이 주어졌을 때__조립하면__새 데이터 자원을 만들지 않는다`() {
        // given
        val jdbc = mockk<JdbcTemplate>()
        val writerManager = mockk<PlatformTransactionManager>()

        excludeRecords { writerManager.hashCode() }
        justRun { jdbc.afterPropertiesSet() }

        // when
        runner(jdbc, writerManager).run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(UserAnimalActivities::class.java)
            assertThat(context.getBeansOfType(DataSource::class.java)).hasSize(1)
            confirmVerified(writerManager)
        }
    }

    private fun runner(jdbc: JdbcTemplate, writerManager: PlatformTransactionManager): ApplicationContextRunner {
        return ApplicationContextRunner()
            .withUserConfiguration(UserAnimalActivitiesConfiguration::class.java)
            .withPropertyValues("pawbridge.user-animal-activities.enabled=true")
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            .withBean("writeTransactionManager", PlatformTransactionManager::class.java, Supplier { writerManager })
            .withBean("dataSource", DataSource::class.java, Supplier { mockk<DataSource>() })
            .withBean(
                "transactionManager",
                PlatformTransactionManager::class.java,
                Supplier { mockk<PlatformTransactionManager>() },
            )
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
    }
}
