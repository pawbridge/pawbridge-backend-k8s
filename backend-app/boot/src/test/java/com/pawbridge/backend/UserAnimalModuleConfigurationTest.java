package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.api.AnimalMemberReads;
import com.pawbridge.backend.animal.api.AnimalShelterReads;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads;
import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterReads;
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadController;
import com.pawbridge.backend.animal.internal.shelter.web.ShelterDiscoveryReadController;
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration;
import com.pawbridge.backend.boot.configuration.ShelterMembershipConfiguration;
import com.pawbridge.backend.boot.configuration.UserAnimalActivitiesConfiguration;
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities;
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership;
import java.net.http.HttpClient;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class UserAnimalModuleConfigurationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(ShelterMembershipConfiguration.class,
                UserAnimalActivitiesConfiguration.class, AnimalShelterConfiguration.class);
    }
    private ApplicationContextRunner inputs() {
        return runner().withBean("dataSource",DataSource.class,()->mock(DataSource.class))
                .withBean("transactionManager",PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class))
                .withBean("writeJdbc",JdbcTemplate.class,()->mock(JdbcTemplate.class))
                .withBean("writeTransactionManager",PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class))
                .withBean("readClock",Clock.class,Clock::systemUTC).withBean(ObjectMapper.class,ObjectMapper::new);
    }

    @Test
    void given__features_off_but_module_properties__when__assembling_without_inputs__then__no_imported_api_http_or_public_routes() {
        runner().withPropertyValues("pawbridge.shelter-membership.animal-backend=module", "pawbridge.user-animal-activities.animal-backend=module")
                .run(context->{
                    assertThat(context).hasNotFailed().doesNotHaveBean(AnimalShelterReads.class)
                            .doesNotHaveBean(AnimalMemberReads.class).doesNotHaveBean(HttpClient.class)
                            .doesNotHaveBean(ShelterMembership.class).doesNotHaveBean(UserAnimalActivities.class)
                            .doesNotHaveBean(ShelterReadController.class).doesNotHaveBean(DataSource.class);
                });
    }

    @Test
    void given__both_features_enabled_with_default_http__when__assembling__then__keep_http_without_internal_animal_api() {
        inputs().withPropertyValues("pawbridge.shelter-membership.enabled=true", "pawbridge.user-animal-activities.enabled=true",
                "pawbridge.shelter-membership.animal-base-url=http://127.0.0.1:1", "service.animal.url=http://127.0.0.1:1")
                .run(context->{
                    assertThat(context).hasNotFailed().doesNotHaveBean(AnimalShelterReads.class).doesNotHaveBean(AnimalMemberReads.class);
                    assertThat(context.getBeansOfType(HttpClient.class)).hasSize(2);
                    assertThat(context.getBean(ShelterMembership.ShelterLookup.class)).isInstanceOf(com.pawbridge.backend.user.internal.shelter.infrastructure.HttpShelterLookup.class);
                    assertThat(context.getBean(UserAnimalActivities.AnimalLookup.class)).isInstanceOf(com.pawbridge.backend.user.internal.animalactivity.infrastructure.HttpUserAnimalLookup.class);
                });
    }

    @ParameterizedTest @CsvSource({"true,false","false,true","true,true"})
    void given__explicit_module_and_public_flags_off__when__assembling_without_origin__then__share_reader_api_and_keep_user_writer_and_public_controllers_separate(boolean shelter,boolean activity) {
        inputs().withPropertyValues("pawbridge.shelter-membership.enabled="+shelter,"pawbridge.user-animal-activities.enabled="+activity,
                "pawbridge.shelter-membership.animal-backend=module","pawbridge.user-animal-activities.animal-backend=module")
                .run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(AnimalShelterReads.class).doesNotHaveBean(HttpClient.class)
                            .doesNotHaveBean(ShelterReadController.class).doesNotHaveBean(ShelterDiscoveryReadController.class);
                    var api=context.getBean(AnimalShelterReads.class);
                    Object registration=activity?ReflectionTestUtils.getField(api,"shelters"):api;
                    assertThat(ReflectionTestUtils.getField(registration,"shelters")).isInstanceOf(JdbcShelterReads.class);
                    var reads=(TransactionTemplate)ReflectionTestUtils.getField(registration,"reads");
                    assertThat(reads.getTransactionManager()).isSameAs(context.getBean("transactionManager"));
                    assertThat(reads.isReadOnly()).isTrue();assertThat(reads.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    if(shelter) {
                        var lookup=context.getBean(ShelterMembership.ShelterLookup.class);
                        assertThat(ReflectionTestUtils.getField(lookup,"shelters")).isSameAs(api);
                        var service=context.getBean(ShelterMembership.class);
                        assertThat(((TransactionTemplate)ReflectionTestUtils.getField(service,"write")).getTransactionManager()).isSameAs(context.getBean("writeTransactionManager"));
                    }
                    if(activity) {
                        assertThat(api).isInstanceOf(AnimalMemberReads.class);
                        assertThat(ReflectionTestUtils.getField(context.getBean(UserAnimalActivities.AnimalLookup.class),"animals")).isSameAs(api);
                        var memberReads=(TransactionTemplate)ReflectionTestUtils.getField(api,"reads");
                        assertThat(memberReads.getTransactionManager()).isSameAs(context.getBean("transactionManager"));
                        assertThat(memberReads.isReadOnly()).isTrue();assertThat(memberReads.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                        assertThat(((TransactionTemplate)ReflectionTestUtils.getField(context.getBean(UserAnimalActivities.class),"write")).getTransactionManager()).isSameAs(context.getBean("writeTransactionManager"));
                    } else assertThat(context.getBeansOfType(AnimalMemberReads.class)).isEmpty();
                    assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
                    assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(2);
                    verify(context.getBean("transactionManager",PlatformTransactionManager.class),never()).getTransaction(any());
                    verify(context.getBean("writeTransactionManager",PlatformTransactionManager.class),never()).getTransaction(any());
                });
    }

    @Test
    void given__module_member_reads_and_local_delivery_enabled__when__assembling_together__then__share_animal_api_and_keep_reader_and_writer_transactions_separate() {
        inputs().withBean("writeDataSource", DataSource.class, () -> mock(DataSource.class))
                .withUserConfiguration(com.pawbridge.backend.boot.configuration.FavoriteDeliveryConfiguration.class)
                .withPropertyValues("pawbridge.shelter-membership.enabled=true",
                        "pawbridge.shelter-membership.animal-backend=module",
                        "pawbridge.user-animal-activities.enabled=true",
                        "pawbridge.user-animal-activities.animal-backend=module",
                        "pawbridge.favorite-delivery.enabled=true",
                        "pawbridge.favorite-delivery.user-outbox-floor=12",
                        "pawbridge.favorite-delivery.animal-outbox-floor=23",
                        "pawbridge.favorite-delivery.delay-ms=3600000")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AnimalMemberReads.class)
                            .hasSingleBean(AnimalShelterReads.class)
                            .hasSingleBean(com.pawbridge.backend.animal.api.AnimalFavoriteEvents.class)
                            .hasSingleBean(com.pawbridge.backend.user.internal.favoritedelivery.infrastructure.JdbcFavoriteEventDelivery.class)
                            .hasSingleBean(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class)
                            .doesNotHaveBean(HttpClient.class).doesNotHaveBean(ShelterReadController.class)
                            .doesNotHaveBean(ShelterDiscoveryReadController.class);
                    var members = context.getBean(AnimalMemberReads.class);
                    assertThat(context.getBean(AnimalShelterReads.class)).isSameAs(members);
                    assertThat(ReflectionTestUtils.getField(context.getBean(UserAnimalActivities.AnimalLookup.class), "animals"))
                            .isSameAs(members);
                    var reader = (TransactionTemplate) ReflectionTestUtils.getField(members, "reads");
                    var actor = context.getBean(com.pawbridge.backend.animal.api.AnimalFavoriteEvents.class);
                    var writer = (TransactionTemplate) ReflectionTestUtils.getField(actor, "transactions");
                    assertThat(reader.getTransactionManager()).isSameAs(context.getBean("transactionManager"));
                    assertThat(reader.isReadOnly()).isTrue();
                    assertThat(writer.getTransactionManager()).isSameAs(context.getBean("writeTransactionManager"))
                            .isNotSameAs(reader.getTransactionManager());
                    assertThat(writer.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
                    verify(context.getBean("transactionManager", PlatformTransactionManager.class), never()).getTransaction(any());
                    verify(context.getBean("writeTransactionManager", PlatformTransactionManager.class), never()).getTransaction(any());
                    verify(context.getBean("writeJdbc", JdbcTemplate.class)).afterPropertiesSet();
                    verifyNoMoreInteractions(context.getBean("writeJdbc"));
                });
    }

    @Test
    void given__an_existing_shelter_reader__when__module_is_selected__then__reuse_that_delegate_without_public_route_activation() {
        var existing=mock(ShelterReads.class);
        inputs().withBean(ShelterReads.class,()->existing).withPropertyValues("pawbridge.shelter-membership.enabled=true","pawbridge.shelter-membership.animal-backend=module")
                .run(context->{assertThat(context).hasNotFailed();
                    assertThat(ReflectionTestUtils.getField(context.getBean(AnimalShelterReads.class),"shelters")).isSameAs(existing);
                    assertThat(context).doesNotHaveBean(ShelterReadController.class);verifyNoInteractions(existing);
                });
    }

    @ParameterizedTest @ValueSource(strings={"pawbridge.shelter-membership","pawbridge.user-animal-activities"})
    void given__unknown_backend_on_an_enabled_feature__when__assembling__then__fail_closed(String feature) {
        inputs().withPropertyValues(feature+".enabled=true",feature+".animal-backend=unexpected")
                .run(context->{assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasRootCauseMessage("Unknown User Animal backend");});
    }
}
