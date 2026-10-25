package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.api.AnimalMemberReads
import com.pawbridge.backend.animal.api.AnimalShelterReads
import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterReads
import com.pawbridge.backend.animal.internal.shelter.web.ShelterDiscoveryReadController
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadController
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration
import com.pawbridge.backend.boot.configuration.ShelterMembershipConfiguration
import com.pawbridge.backend.boot.configuration.UserAnimalActivitiesConfiguration
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities
import com.pawbridge.backend.user.internal.animalactivity.infrastructure.ModuleUserAnimalLookup
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import com.pawbridge.backend.user.internal.shelter.infrastructure.ModuleShelterLookup
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
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
    fun `보호소 멤버십과 동물 활동이 주어졌을 때__구성하면__리더 API 하나를 공유하고 사용자 writer와 분리한다`() {
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
                val api = context.getBean(AnimalShelterReads::class.java)
                assertThat(api).isInstanceOf(AnimalMemberReads::class.java)
                val registration = ReflectionTestUtils.getField(api, "shelters")
                assertThat(registration).isNotNull()
                val delegate = ReflectionTestUtils.getField(registration!!, "shelters")
                assertThat(delegate).isInstanceOf(JdbcShelterReads::class.java)
                val reads = ReflectionTestUtils.getField(registration, "reads") as TransactionTemplate
                assertThat(reads.transactionManager).isSameAs(readerManager)
                assertThat(reads.isReadOnly).isTrue()
                assertThat(reads.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)

                val shelterLookup = context.getBean(ShelterMembership.ShelterLookup::class.java)
                assertThat(shelterLookup).isInstanceOf(ModuleShelterLookup::class.java)
                assertThat(ReflectionTestUtils.getField(shelterLookup, "shelters")).isSameAs(api)
                val membershipWrites = ReflectionTestUtils.getField(context.getBean(ShelterMembership::class.java), "write")
                    as TransactionTemplate
                assertThat(membershipWrites.transactionManager).isSameAs(writerManager)

                assertThat(context.getBean(UserAnimalActivities.AnimalLookup::class.java))
                    .isInstanceOf(ModuleUserAnimalLookup::class.java)
                assertThat(context.getBeansOfType(DataSource::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PlatformTransactionManager::class.java)).hasSize(2)
                verify(exactly = 0) { readerManager.getTransaction(any<TransactionDefinition>()) }
                verify(exactly = 0) { writerManager.getTransaction(any<TransactionDefinition>()) }
            }
    }
}
