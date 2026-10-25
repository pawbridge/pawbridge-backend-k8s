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
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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

    private fun inputs(writeJdbc: JdbcTemplate): ApplicationContextRunner {
        return runner()
            .withBean("dataSource", DataSource::class.java, Supplier { mockk<DataSource>() })
            .withBean(
                "transactionManager",
                PlatformTransactionManager::class.java,
                Supplier { mockk<PlatformTransactionManager>() },
            )
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { writeJdbc })
            .withBean(
                "writeTransactionManager",
                PlatformTransactionManager::class.java,
                Supplier { mockk<PlatformTransactionManager>() },
            )
            .withBean("readClock", Clock::class.java, Supplier { Clock.systemUTC() })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
    }

    @Test
    fun `기능이 꺼지고 입력이 없을 때__조립하면__가져온 API도 공개 경로도 없다`() {
        // given
        val runner = runner()

        // when
        runner.run { context ->
            // then
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
    fun `공개 플래그가 꺼졌을 때__조립하면__리더 API를 공유하고 사용자 쓰기와 공개 컨트롤러를 분리한다`(
        shelter: Boolean,
        activity: Boolean,
    ) {
        // given
        val writeJdbc = mockk<JdbcTemplate>()
        justRun { writeJdbc.afterPropertiesSet() }

        // when
        inputs(writeJdbc)
            .withPropertyValues(
                "pawbridge.shelter-membership.enabled=$shelter",
                "pawbridge.user-animal-activities.enabled=$activity",
            )
            .run { context ->
                // then
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
                val readManager = context.getBean("transactionManager", PlatformTransactionManager::class.java)
                val writeManager = context.getBean("writeTransactionManager", PlatformTransactionManager::class.java)
                verify(exactly = 0) { readManager.getTransaction(any()) }
                verify(exactly = 0) { writeManager.getTransaction(any()) }
            }
    }

    @Test
    fun `활동이 켜졌을 때__조립하면__스케줄러와 자체 트랜잭션 없이 쓰기 쪽에 좋아요 수 API를 연결한다`() {
        // given
        val writeJdbc = mockk<JdbcTemplate>()

        justRun { writeJdbc.afterPropertiesSet() }

        // when
        inputs(writeJdbc)
            .withBean("writeDataSource", DataSource::class.java, Supplier { mockk<DataSource>() })
            .withPropertyValues("pawbridge.user-animal-activities.enabled=true")
            .run { context ->
                // then
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

                val readManager = context.getBean("transactionManager", PlatformTransactionManager::class.java)
                val writeManager = context.getBean("writeTransactionManager", PlatformTransactionManager::class.java)
                verify(exactly = 0) { readManager.getTransaction(any()) }
                verify(exactly = 0) { writeManager.getTransaction(any()) }
                verify(exactly = 1) { writeJdbc.afterPropertiesSet() }
                confirmVerified(writeJdbc)
            }
    }

    @Test
    fun `활동이 꺼졌을 때__조립하면__좋아요 수 API가 없다`() {
        // given
        val writeJdbc = mockk<JdbcTemplate>()
        justRun { writeJdbc.afterPropertiesSet() }

        // when
        inputs(writeJdbc).run { context ->
            // then
            assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(AnimalFavoriteCounts::class.java)
        }
    }

    @Test
    fun `기존 보호소 리더가 주어졌을 때__조립하면__공개 경로 활성화 없이 그 위임체를 재사용한다`() {
        // given
        val existing = mockk<ShelterReads>()
        excludeRecords { existing.hashCode() }
        val writeJdbc = mockk<JdbcTemplate>()
        justRun { writeJdbc.afterPropertiesSet() }

        // when
        inputs(writeJdbc)
            .withBean(ShelterReads::class.java, Supplier { existing })
            .withPropertyValues("pawbridge.shelter-membership.enabled=true")
            .run { context ->
                // then
                assertThat(context).hasNotFailed()
                assertThat(ReflectionTestUtils.getField(context.getBean(AnimalShelterReads::class.java), "shelters"))
                    .isSameAs(existing)
                assertThat(context).doesNotHaveBean(ShelterReadController::class.java)
                confirmVerified(existing)
            }
    }
}
