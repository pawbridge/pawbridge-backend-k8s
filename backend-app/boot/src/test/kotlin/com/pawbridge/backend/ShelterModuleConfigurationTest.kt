package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.api.AnimalShelterReads
import com.pawbridge.backend.animal.internal.shelter.web.ShelterDiscoveryReadController
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadController
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration
import com.pawbridge.backend.boot.configuration.ShelterMembershipConfiguration
import com.pawbridge.backend.boot.configuration.UserAnimalActivitiesConfiguration
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import java.time.Clock
import java.util.function.Supplier
import javax.sql.DataSource

class ShelterModuleConfigurationTest {
    private fun runner(): ApplicationContextRunner {
        return ApplicationContextRunner().withUserConfiguration(
            ShelterMembershipConfiguration::class.java,
            UserAnimalActivitiesConfiguration::class.java,
            AnimalShelterConfiguration::class.java,
        )
    }

    @Test
    fun `기능이 꺼져 있을 때__입력 없이 구성하면__API와 공개 빈이 없다`() {
        // given
        // when
        runner().run { context ->
            // then
            assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(AnimalShelterReads::class.java)
                .doesNotHaveBean(ShelterMembership::class.java)
                .doesNotHaveBean(UserAnimalActivities::class.java)
                .doesNotHaveBean(DataSource::class.java)
        }
    }

    @Test
    fun `보호소 멤버십과 동물 활동이 주어졌을 때__구성하면__데이터 자원을 새로 만들지 않고 연결을 열지 않는다`() {
        // given
        val reader = mockk<DataSource>()
        val readerManager = mockk<PlatformTransactionManager>()
        val writer = mockk<JdbcTemplate>()
        val writerManager = mockk<PlatformTransactionManager>()

        justRun { writer.afterPropertiesSet() }

        // when
        runner()
            .withBean("dataSource", DataSource::class.java, Supplier { reader })
            .withBean("transactionManager", PlatformTransactionManager::class.java, Supplier { readerManager })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { writer })
            .withBean("writeTransactionManager", PlatformTransactionManager::class.java, Supplier { writerManager })
            .withBean("readClock", Clock::class.java, Supplier { Clock.systemUTC() })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .withPropertyValues(
                "pawbridge.shelter-membership.enabled=true",
                "pawbridge.user-animal-activities.enabled=true",
            )
            .run { context ->
                // then
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(AnimalShelterReads::class.java)
                    .doesNotHaveBean(ShelterReadController::class.java)
                    .doesNotHaveBean(ShelterDiscoveryReadController::class.java)
                assertThat(context.getBeansOfType(DataSource::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PlatformTransactionManager::class.java)).hasSize(2)
                verify(exactly = 0) { readerManager.getTransaction(any<TransactionDefinition>()) }
                verify(exactly = 0) { writerManager.getTransaction(any<TransactionDefinition>()) }
            }
    }
}
