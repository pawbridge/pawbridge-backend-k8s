package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.api.AnimalMemberReads;
import com.pawbridge.backend.animal.api.AnimalShelterReads;
import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterReads;
import com.pawbridge.backend.animal.internal.shelter.web.ShelterDiscoveryReadController;
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadController;
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration;
import com.pawbridge.backend.boot.configuration.ShelterMembershipConfiguration;
import com.pawbridge.backend.boot.configuration.UserAnimalActivitiesConfiguration;
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities;
import com.pawbridge.backend.user.internal.animalactivity.infrastructure.ModuleUserAnimalLookup;
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership;
import com.pawbridge.backend.user.internal.shelter.infrastructure.ModuleShelterLookup;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class ShelterModuleConfigurationTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(
                ShelterMembershipConfiguration.class,
                UserAnimalActivitiesConfiguration.class,
                AnimalShelterConfiguration.class);
    }

    @Test
    void given__features_off__when__assembling_without_inputs__then__no_api_or_public_bean() {
        runner().run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(AnimalShelterReads.class)
                .doesNotHaveBean(ShelterMembership.class)
                .doesNotHaveBean(UserAnimalActivities.class)
                .doesNotHaveBean(DataSource.class));
    }

    @Test
    void given__shelter_membership_and_animal_activities__when__assembling__then__share_one_reader_api_and_separate_it_from_the_user_writer() {
        var reader = mock(DataSource.class);
        var readerManager = mock(PlatformTransactionManager.class);
        var writer = mock(JdbcTemplate.class);
        var writerManager = mock(PlatformTransactionManager.class);

        runner()
                .withBean("dataSource", DataSource.class, () -> reader)
                .withBean("transactionManager", PlatformTransactionManager.class, () -> readerManager)
                .withBean("writeJdbc", JdbcTemplate.class, () -> writer)
                .withBean("writeTransactionManager", PlatformTransactionManager.class, () -> writerManager)
                .withBean("readClock", Clock.class, Clock::systemUTC)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(
                        "pawbridge.shelter-membership.enabled=true",
                        "pawbridge.user-animal-activities.enabled=true")
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(AnimalShelterReads.class)
                            .doesNotHaveBean(ShelterReadController.class)
                            .doesNotHaveBean(ShelterDiscoveryReadController.class);
                    var api = context.getBean(AnimalShelterReads.class);
                    assertThat(api).isInstanceOf(AnimalMemberReads.class);
                    var registration = ReflectionTestUtils.getField(api, "shelters");
                    assertThat(registration).isNotNull();
                    var delegate = ReflectionTestUtils.getField(registration, "shelters");
                    assertThat(delegate).isInstanceOf(JdbcShelterReads.class);
                    var reads = (TransactionTemplate) ReflectionTestUtils.getField(registration, "reads");
                    assertThat(reads.getTransactionManager()).isSameAs(readerManager);
                    assertThat(reads.isReadOnly()).isTrue();
                    assertThat(reads.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);

                    var shelterLookup = context.getBean(ShelterMembership.ShelterLookup.class);
                    assertThat(shelterLookup).isInstanceOf(ModuleShelterLookup.class);
                    assertThat(ReflectionTestUtils.getField(shelterLookup, "shelters")).isSameAs(api);
                    var membershipWrites = (TransactionTemplate) ReflectionTestUtils
                            .getField(context.getBean(ShelterMembership.class), "write");
                    assertThat(membershipWrites.getTransactionManager()).isSameAs(writerManager);

                    assertThat(context.getBean(UserAnimalActivities.AnimalLookup.class))
                            .isInstanceOf(ModuleUserAnimalLookup.class);
                    assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
                    assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(2);
                    verify(readerManager, never()).getTransaction(any());
                    verify(writerManager, never()).getTransaction(any());
                });
    }
}
