package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.api.AnimalShelterReads;
import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterReads;
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadController;
import com.pawbridge.backend.animal.internal.shelter.web.ShelterDiscoveryReadController;
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration;
import com.pawbridge.backend.boot.configuration.ShelterMembershipConfiguration;
import com.pawbridge.backend.boot.configuration.UserAnimalActivitiesConfiguration;
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities;
import com.pawbridge.backend.user.internal.animalactivity.infrastructure.HttpUserAnimalLookup;
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership;
import com.pawbridge.backend.user.internal.shelter.infrastructure.ModuleShelterLookup;
import java.net.http.HttpClient;
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
        return new ApplicationContextRunner().withUserConfiguration(ShelterMembershipConfiguration.class,
                UserAnimalActivitiesConfiguration.class,AnimalShelterConfiguration.class);
    }

    @Test
    void given__shelter_feature_off_with_module_property__when__assembling_without_inputs__then__no_api_or_public_or_http_bean() {
        runner().withPropertyValues("pawbridge.shelter-membership.animal-backend=module")
                .run(context->assertThat(context).hasNotFailed().doesNotHaveBean(AnimalShelterReads.class)
                        .doesNotHaveBean(ShelterMembership.class).doesNotHaveBean(UserAnimalActivities.class)
                        .doesNotHaveBean(HttpClient.class).doesNotHaveBean(DataSource.class));
    }

    @Test
    void given__shelter_module_and_existing_activity_http__when__assembling_without_shelter_origin__then__keep_only_registration_api_and_separate_reader_from_user_writer() {
        var reader=mock(DataSource.class);var readerManager=mock(PlatformTransactionManager.class);
        var writer=mock(JdbcTemplate.class);var writerManager=mock(PlatformTransactionManager.class);
        runner().withBean("dataSource",DataSource.class,()->reader)
                .withBean("transactionManager",PlatformTransactionManager.class,()->readerManager)
                .withBean("writeJdbc",JdbcTemplate.class,()->writer)
                .withBean("writeTransactionManager",PlatformTransactionManager.class,()->writerManager)
                .withBean("readClock",Clock.class,Clock::systemUTC).withBean(ObjectMapper.class,ObjectMapper::new)
                .withPropertyValues("pawbridge.shelter-membership.enabled=true","pawbridge.shelter-membership.animal-backend=module",
                        "pawbridge.user-animal-activities.enabled=true","service.animal.url=http://127.0.0.1:1")
                .run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(AnimalShelterReads.class)
                            .doesNotHaveBean(ShelterReadController.class).doesNotHaveBean(ShelterDiscoveryReadController.class);
                    var api=context.getBean(AnimalShelterReads.class);
                    var delegate=ReflectionTestUtils.getField(api,"shelters");assertThat(delegate).isInstanceOf(JdbcShelterReads.class);
                    var reads=(TransactionTemplate)ReflectionTestUtils.getField(api,"reads");
                    assertThat(reads.getTransactionManager()).isSameAs(readerManager);assertThat(reads.isReadOnly()).isTrue();
                    assertThat(reads.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    var lookup=context.getBean(ShelterMembership.ShelterLookup.class);assertThat(lookup).isInstanceOf(ModuleShelterLookup.class);
                    assertThat(ReflectionTestUtils.getField(lookup,"shelters")).isSameAs(api);
                    assertThat(((TransactionTemplate)ReflectionTestUtils.getField(context.getBean(ShelterMembership.class),"write")).getTransactionManager()).isSameAs(writerManager);
                    assertThat(context.getBean(UserAnimalActivities.AnimalLookup.class)).isInstanceOf(HttpUserAnimalLookup.class);
                    assertThat(context.getBeansOfType(HttpClient.class)).hasSize(1);
                    assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
                    assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(2);
                    verify(readerManager,never()).getTransaction(any());verify(writerManager,never()).getTransaction(any());
                });
    }
}
