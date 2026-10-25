package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.api.AnimalMemberReads
import com.pawbridge.backend.boot.configuration.UserAnimalActivitiesConfiguration
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
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
    fun given__no_activation__when__assembling__then__create_no_activity_beans(flag: String) {
        var runner = ApplicationContextRunner().withUserConfiguration(UserAnimalActivitiesConfiguration::class.java)
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.user-animal-activities.enabled=false")
        }

        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(UserAnimalActivities::class.java)
            assertThat(context).doesNotHaveBean(AnimalMemberReads::class.java)
        }
    }

    @Test
    fun given__existing_writer__when__assembling__then__reuse_transactions_and_avoid_new_data_resources() {
        val jdbc = mock(JdbcTemplate::class.java)
        val writerManager = mock(PlatformTransactionManager::class.java)

        runner(jdbc, writerManager).run { context ->
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
            verify(jdbc).afterPropertiesSet()
            verifyNoMoreInteractions(jdbc)
            verifyNoInteractions(writerManager)
        }
    }

    private fun runner(jdbc: JdbcTemplate, writerManager: PlatformTransactionManager): ApplicationContextRunner {
        return ApplicationContextRunner()
            .withUserConfiguration(UserAnimalActivitiesConfiguration::class.java)
            .withPropertyValues("pawbridge.user-animal-activities.enabled=true")
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            .withBean("writeTransactionManager", PlatformTransactionManager::class.java, Supplier { writerManager })
            .withBean("dataSource", DataSource::class.java, Supplier { mock(DataSource::class.java) })
            .withBean(
                "transactionManager",
                PlatformTransactionManager::class.java,
                Supplier { mock(PlatformTransactionManager::class.java) },
            )
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
    }
}
