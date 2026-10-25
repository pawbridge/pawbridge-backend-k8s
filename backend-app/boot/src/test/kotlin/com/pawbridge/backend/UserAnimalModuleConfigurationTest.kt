package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.api.AnimalFavoriteCounts
import com.pawbridge.backend.animal.api.AnimalMemberReads
import com.pawbridge.backend.animal.api.AnimalShelterReads
import com.pawbridge.backend.animal.internal.favorite.infrastructure.JdbcAnimalFavoriteCounts
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterReads
import com.pawbridge.backend.animal.internal.shelter.web.ShelterDiscoveryReadController
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadController
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration
import com.pawbridge.backend.boot.configuration.ShelterMembershipConfiguration
import com.pawbridge.backend.boot.configuration.UserAnimalActivitiesConfiguration
import com.pawbridge.backend.support.anyValue
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.function.Supplier
import javax.sql.DataSource

class UserAnimalModuleConfigurationTest {

    private fun runner(): ApplicationContextRunner {
        return ApplicationContextRunner().withUserConfiguration(
            ShelterMembershipConfiguration::class.java,
            UserAnimalActivitiesConfiguration::class.java,
            AnimalShelterConfiguration::class.java,
        )
    }

    private fun inputs(): ApplicationContextRunner {
        return runner()
            .withBean("dataSource", DataSource::class.java, Supplier { mock(DataSource::class.java) })
            .withBean(
                "transactionManager",
                PlatformTransactionManager::class.java,
                Supplier { mock(PlatformTransactionManager::class.java) },
            )
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { mock(JdbcTemplate::class.java) })
            .withBean(
                "writeTransactionManager",
                PlatformTransactionManager::class.java,
                Supplier { mock(PlatformTransactionManager::class.java) },
            )
            .withBean("readClock", Clock::class.java, Supplier { Clock.systemUTC() })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
    }

    @Test
    fun given__features_off__when__assembling_without_inputs__then__no_imported_api_or_public_routes() {
        runner().run { context ->
            assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(AnimalShelterReads::class.java)
                .doesNotHaveBean(AnimalMemberReads::class.java)
                .doesNotHaveBean(ShelterMembership::class.java)
                .doesNotHaveBean(UserAnimalActivities::class.java)
                .doesNotHaveBean(ShelterReadController::class.java)
                .doesNotHaveBean(DataSource::class.java)
        }
    }

    @ParameterizedTest
    @CsvSource("true,false", "false,true", "true,true")
    fun given__public_flags_off__when__assembling__then__share_reader_api_and_keep_user_writer_and_public_controllers_separate(
        shelter: Boolean,
        activity: Boolean,
    ) {
        inputs()
            .withPropertyValues(
                "pawbridge.shelter-membership.enabled=$shelter",
                "pawbridge.user-animal-activities.enabled=$activity",
            )
            .run { context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(AnimalShelterReads::class.java)
                    .doesNotHaveBean(ShelterReadController::class.java)
                    .doesNotHaveBean(ShelterDiscoveryReadController::class.java)
                val api = context.getBean(AnimalShelterReads::class.java)
                val registration: Any = if (activity) ReflectionTestUtils.getField(api, "shelters")!! else api
                assertThat(ReflectionTestUtils.getField(registration, "shelters"))
                    .isInstanceOf(JdbcShelterReads::class.java)
                val reads = ReflectionTestUtils.getField(registration, "reads") as TransactionTemplate
                assertThat(reads.transactionManager).isSameAs(context.getBean("transactionManager"))
                assertThat(reads.isReadOnly).isTrue()
                assertThat(reads.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)

                if (shelter) {
                    val lookup = context.getBean(ShelterMembership.ShelterLookup::class.java)
                    assertThat(ReflectionTestUtils.getField(lookup, "shelters")).isSameAs(api)
                    val service = context.getBean(ShelterMembership::class.java)
                    val membershipWrites = ReflectionTestUtils.getField(service, "write") as TransactionTemplate
                    assertThat(membershipWrites.transactionManager)
                        .isSameAs(context.getBean("writeTransactionManager"))
                }

                if (activity) {
                    assertThat(api).isInstanceOf(AnimalMemberReads::class.java)
                    val animalLookup = context.getBean(UserAnimalActivities.AnimalLookup::class.java)
                    assertThat(ReflectionTestUtils.getField(animalLookup, "animals")).isSameAs(api)
                    val memberReads = ReflectionTestUtils.getField(api, "reads") as TransactionTemplate
                    assertThat(memberReads.transactionManager).isSameAs(context.getBean("transactionManager"))
                    assertThat(memberReads.isReadOnly).isTrue()
                    assertThat(memberReads.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
                    val activityWrites = ReflectionTestUtils
                        .getField(context.getBean(UserAnimalActivities::class.java), "write") as TransactionTemplate
                    assertThat(activityWrites.transactionManager).isSameAs(context.getBean("writeTransactionManager"))
                } else {
                    assertThat(context.getBeansOfType(AnimalMemberReads::class.java)).isEmpty()
                }

                assertThat(context.getBeansOfType(DataSource::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PlatformTransactionManager::class.java)).hasSize(2)
                verify(context.getBean("transactionManager", PlatformTransactionManager::class.java), never())
                    .getTransaction(anyValue<TransactionDefinition>())
                verify(context.getBean("writeTransactionManager", PlatformTransactionManager::class.java), never())
                    .getTransaction(anyValue<TransactionDefinition>())
            }
    }

    @Test
    fun given__activities_enabled__when__assembling__then__wire_the_favorite_count_api_on_the_writer_without_a_scheduler_or_own_transaction() {
        inputs()
            .withBean("writeDataSource", DataSource::class.java, Supplier { mock(DataSource::class.java) })
            .withPropertyValues("pawbridge.user-animal-activities.enabled=true")
            .run { context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(AnimalFavoriteCounts::class.java)
                    .doesNotHaveBean(ThreadPoolTaskScheduler::class.java)
                val counts = context.getBean(AnimalFavoriteCounts::class.java)
                assertThat(counts).isInstanceOf(JdbcAnimalFavoriteCounts::class.java)
                assertThat(ReflectionTestUtils.getField(counts, "jdbc")).isSameAs(context.getBean("writeJdbc"))

                val activities = context.getBean(UserAnimalActivities::class.java)
                assertThat(ReflectionTestUtils.getField(activities, "counts")).isSameAs(counts)
                assertThat(ReflectionTestUtils.getField(activities, "jdbc")).isSameAs(context.getBean("writeJdbc"))
                val writer = ReflectionTestUtils.getField(activities, "write") as TransactionTemplate
                assertThat(writer.transactionManager).isSameAs(context.getBean("writeTransactionManager"))
                assertThat(writer.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)

                verify(context.getBean("transactionManager", PlatformTransactionManager::class.java), never())
                    .getTransaction(anyValue<TransactionDefinition>())
                verify(context.getBean("writeTransactionManager", PlatformTransactionManager::class.java), never())
                    .getTransaction(anyValue<TransactionDefinition>())
                verify(context.getBean("writeJdbc", JdbcTemplate::class.java)).afterPropertiesSet()
                verifyNoMoreInteractions(context.getBean("writeJdbc"))
            }
    }

    @Test
    fun given__activities_disabled__when__assembling__then__no_favorite_count_api() {
        inputs().run { context ->
            assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(AnimalFavoriteCounts::class.java)
        }
    }

    @Test
    fun given__an_existing_shelter_reader__when__assembling__then__reuse_that_delegate_without_public_route_activation() {
        val existing = mock(ShelterReads::class.java)

        inputs()
            .withBean(ShelterReads::class.java, Supplier { existing })
            .withPropertyValues("pawbridge.shelter-membership.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(ReflectionTestUtils.getField(context.getBean(AnimalShelterReads::class.java), "shelters"))
                    .isSameAs(existing)
                assertThat(context).doesNotHaveBean(ShelterReadController::class.java)
                verifyNoInteractions(existing)
            }
    }
}
