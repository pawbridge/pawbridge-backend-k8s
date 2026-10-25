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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
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
    fun given__features_off__when__assembling_without_inputs__then__no_api_or_public_bean() {
        runner().run { context ->
            assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(AnimalShelterReads::class.java)
                .doesNotHaveBean(ShelterMembership::class.java)
                .doesNotHaveBean(UserAnimalActivities::class.java)
                .doesNotHaveBean(DataSource::class.java)
        }
    }

    @Test
    fun given__shelter_membership_and_animal_activities__when__assembling__then__share_one_reader_api_and_separate_it_from_the_user_writer() {
        val reader = mock(DataSource::class.java)
        val readerManager = mock(PlatformTransactionManager::class.java)
        val writer = mock(JdbcTemplate::class.java)
        val writerManager = mock(PlatformTransactionManager::class.java)

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
                verify(readerManager, never()).getTransaction(any<TransactionDefinition>())
                verify(writerManager, never()).getTransaction(any<TransactionDefinition>())
            }
    }
}
