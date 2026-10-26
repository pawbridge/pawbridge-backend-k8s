package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.api.AnimalMemberReads
import com.pawbridge.backend.boot.configuration.UserAnimalActivitiesConfiguration
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
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
    fun `기존 쓰기 자원이 주어졌을 때__조립하면__트랜잭션을 재사용하고 새 데이터 자원을 만들지 않는다`() {
        // given
        val jdbc = mockk<JdbcTemplate>()
        val writerManager = mockk<PlatformTransactionManager>()

        excludeRecords { writerManager.hashCode() }
        justRun { jdbc.afterPropertiesSet() }

        // when
        runner(jdbc, writerManager).run { context ->
            // then
            assertThat(context).hasNotFailed()
            val activities = context.getBean(UserAnimalActivities::class.java)
            assertThat(ReflectionTestUtils.getField(activities, "jdbc")).isSameAs(jdbc)
            val write = ReflectionTestUtils.getField(activities, "write") as TransactionTemplate
            val read = ReflectionTestUtils.getField(activities, "read") as TransactionTemplate
            assertThat(write.transactionManager).isSameAs(writerManager)
            assertThat(write.isReadOnly).isFalse()
            assertThat(write.timeout).isEqualTo(-1)
            assertThat(read.transactionManager).isSameAs(writerManager)
            assertThat(read.isReadOnly).isTrue()
            assertThat(context.getBeansOfType(DataSource::class.java)).hasSize(1)
            verify(exactly = 1) { jdbc.afterPropertiesSet() }
            confirmVerified(jdbc)
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
