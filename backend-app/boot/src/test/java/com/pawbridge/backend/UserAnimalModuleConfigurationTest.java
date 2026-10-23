package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.api.AnimalFavoriteCounts;
import com.pawbridge.backend.animal.api.AnimalMemberReads;
import com.pawbridge.backend.animal.api.AnimalShelterReads;
import com.pawbridge.backend.animal.internal.favorite.infrastructure.JdbcAnimalFavoriteCounts;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads;
import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterReads;
import com.pawbridge.backend.animal.internal.shelter.web.ShelterDiscoveryReadController;
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadController;
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration;
import com.pawbridge.backend.boot.configuration.ShelterMembershipConfiguration;
import com.pawbridge.backend.boot.configuration.UserAnimalActivitiesConfiguration;
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities;
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class UserAnimalModuleConfigurationTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(
                ShelterMembershipConfiguration.class,
                UserAnimalActivitiesConfiguration.class,
                AnimalShelterConfiguration.class);
    }

    private ApplicationContextRunner inputs() {
        return runner()
                .withBean("dataSource", DataSource.class, () -> mock(DataSource.class))
                .withBean("transactionManager", PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withBean("writeJdbc", JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean("writeTransactionManager", PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withBean("readClock", Clock.class, Clock::systemUTC)
                .withBean(ObjectMapper.class, ObjectMapper::new);
    }

    @Test
    void given__features_off__when__assembling_without_inputs__then__no_imported_api_or_public_routes() {
        runner().run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(AnimalShelterReads.class)
                .doesNotHaveBean(AnimalMemberReads.class)
                .doesNotHaveBean(ShelterMembership.class)
                .doesNotHaveBean(UserAnimalActivities.class)
                .doesNotHaveBean(ShelterReadController.class)
                .doesNotHaveBean(DataSource.class));
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true", "true,true"})
    void given__public_flags_off__when__assembling__then__share_reader_api_and_keep_user_writer_and_public_controllers_separate(
            boolean shelter, boolean activity) {
        inputs()
                .withPropertyValues(
                        "pawbridge.shelter-membership.enabled=" + shelter,
                        "pawbridge.user-animal-activities.enabled=" + activity)
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(AnimalShelterReads.class)
                            .doesNotHaveBean(ShelterReadController.class)
                            .doesNotHaveBean(ShelterDiscoveryReadController.class);
                    var api = context.getBean(AnimalShelterReads.class);
                    Object registration = activity ? ReflectionTestUtils.getField(api, "shelters") : api;
                    assertThat(ReflectionTestUtils.getField(registration, "shelters"))
                            .isInstanceOf(JdbcShelterReads.class);
                    var reads = (TransactionTemplate) ReflectionTestUtils.getField(registration, "reads");
                    assertThat(reads.getTransactionManager()).isSameAs(context.getBean("transactionManager"));
                    assertThat(reads.isReadOnly()).isTrue();
                    assertThat(reads.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);

                    if (shelter) {
                        var lookup = context.getBean(ShelterMembership.ShelterLookup.class);
                        assertThat(ReflectionTestUtils.getField(lookup, "shelters")).isSameAs(api);
                        var service = context.getBean(ShelterMembership.class);
                        var membershipWrites = (TransactionTemplate) ReflectionTestUtils.getField(service, "write");
                        assertThat(membershipWrites.getTransactionManager())
                                .isSameAs(context.getBean("writeTransactionManager"));
                    }

                    if (activity) {
                        assertThat(api).isInstanceOf(AnimalMemberReads.class);
                        var animalLookup = context.getBean(UserAnimalActivities.AnimalLookup.class);
                        assertThat(ReflectionTestUtils.getField(animalLookup, "animals")).isSameAs(api);
                        var memberReads = (TransactionTemplate) ReflectionTestUtils.getField(api, "reads");
                        assertThat(memberReads.getTransactionManager())
                                .isSameAs(context.getBean("transactionManager"));
                        assertThat(memberReads.isReadOnly()).isTrue();
                        assertThat(memberReads.getPropagationBehavior())
                                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
                        var activityWrites = (TransactionTemplate) ReflectionTestUtils
                                .getField(context.getBean(UserAnimalActivities.class), "write");
                        assertThat(activityWrites.getTransactionManager())
                                .isSameAs(context.getBean("writeTransactionManager"));
                    } else {
                        assertThat(context.getBeansOfType(AnimalMemberReads.class)).isEmpty();
                    }

                    assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
                    assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(2);
                    verify(context.getBean("transactionManager", PlatformTransactionManager.class), never())
                            .getTransaction(any());
                    verify(context.getBean("writeTransactionManager", PlatformTransactionManager.class), never())
                            .getTransaction(any());
                });
    }

    @Test
    void given__activities_enabled__when__assembling__then__wire_the_favorite_count_api_on_the_writer_without_a_scheduler_or_own_transaction() {
        inputs()
                .withBean("writeDataSource", DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues("pawbridge.user-animal-activities.enabled=true")
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(AnimalFavoriteCounts.class)
                            .doesNotHaveBean(ThreadPoolTaskScheduler.class);
                    var counts = context.getBean(AnimalFavoriteCounts.class);
                    assertThat(counts).isInstanceOf(JdbcAnimalFavoriteCounts.class);
                    assertThat(ReflectionTestUtils.getField(counts, "jdbc")).isSameAs(context.getBean("writeJdbc"));

                    var activities = context.getBean(UserAnimalActivities.class);
                    assertThat(ReflectionTestUtils.getField(activities, "counts")).isSameAs(counts);
                    assertThat(ReflectionTestUtils.getField(activities, "jdbc"))
                            .isSameAs(context.getBean("writeJdbc"));
                    var writer = (TransactionTemplate) ReflectionTestUtils.getField(activities, "write");
                    assertThat(writer.getTransactionManager()).isSameAs(context.getBean("writeTransactionManager"));
                    assertThat(writer.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);

                    verify(context.getBean("transactionManager", PlatformTransactionManager.class), never())
                            .getTransaction(any());
                    verify(context.getBean("writeTransactionManager", PlatformTransactionManager.class), never())
                            .getTransaction(any());
                    verify(context.getBean("writeJdbc", JdbcTemplate.class)).afterPropertiesSet();
                    verifyNoMoreInteractions(context.getBean("writeJdbc"));
                });
    }

    @Test
    void given__activities_disabled__when__assembling__then__no_favorite_count_api() {
        inputs().run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(AnimalFavoriteCounts.class));
    }

    @Test
    void given__an_existing_shelter_reader__when__assembling__then__reuse_that_delegate_without_public_route_activation() {
        var existing = mock(ShelterReads.class);

        inputs()
                .withBean(ShelterReads.class, () -> existing)
                .withPropertyValues("pawbridge.shelter-membership.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(ReflectionTestUtils.getField(context.getBean(AnimalShelterReads.class), "shelters"))
                            .isSameAs(existing);
                    assertThat(context).doesNotHaveBean(ShelterReadController.class);
                    verifyNoInteractions(existing);
                });
    }
}
