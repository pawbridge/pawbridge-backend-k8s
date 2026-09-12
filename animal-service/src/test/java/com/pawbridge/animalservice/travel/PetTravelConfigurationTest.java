package com.pawbridge.animalservice.travel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PetTravelConfigurationTest {
    @Test void givenDefaults__whenConfigured__thenUseBoundedCollectionAndFourteenDayRefresh() {
        var properties=new TourApiProperties();
        assertThat(properties.getMaxPagesPerRun()).isEqualTo(10);
        assertThat(properties.getMaxDetailsPerRun()).isEqualTo(18);
        assertThat(properties.getDailyRequestLimit()).isEqualTo(900);
        assertThat(properties.getDetailRefreshDays()).isEqualTo(14);
    }
    @Test void givenRefreshOverride__whenBound__thenUseConfiguredDays() {
        var environment=new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",
                java.util.Map.of("tourapi.detail-refresh-days","21","tourapi.max-details-per-run","19","tourapi.daily-request-limit","950")));
        var properties=org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("tourapi",org.springframework.boot.context.properties.bind.Bindable.of(TourApiProperties.class)).get();
        assertThat(properties.getDetailRefreshDays()).isEqualTo(21);
        assertThat(properties.getMaxDetailsPerRun()).isEqualTo(19);
        assertThat(properties.getDailyRequestLimit()).isEqualTo(950);
    }
    @Test void givenNonpositiveRefreshDays__whenConfigured__thenReject() {
        for (int days : new int[]{0,-1}) {
            assertThatThrownBy(()->new TourApiProperties().setDetailRefreshDays(days))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void givenScheduleNotEnabled__whenContextStarts__thenNoJobLauncherIsCreated() {
        new ApplicationContextRunner().withUserConfiguration(PetTravelCollectionSchedule.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PetTravelCollectionSchedule.class);
                });
        new ApplicationContextRunner().withUserConfiguration(PetTravelCollectionSchedule.class)
                .withPropertyValues("tourapi.schedule-enabled=false").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PetTravelCollectionSchedule.class);
                });
    }
    @Test void givenVaultEnvironmentKey__whenBound__thenKeyLoadedAndCollectionRemainsDisabled() {
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().replace(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new org.springframework.core.env.SystemEnvironmentPropertySource(
                org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                java.util.Map.of("TOURAPI_SERVICEKEY", "syntheticOnly1234")));
        var properties = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("tourapi", org.springframework.boot.context.properties.bind.Bindable.of(TourApiProperties.class)).get();
        assertThat(properties.getServiceKey()).isEqualTo("syntheticOnly1234");
        assertThat(properties.isEnabled()).isFalse();
    }
    @Test void givenNoTourApiOrRedisBeans__whenPublicContextStarts__thenStoredReadsWork() {
        new ApplicationContextRunner().withUserConfiguration(PublicConfig.class)
                .withBean(PetTravelCatalog.class,()->{
                    var catalog=mock(PetTravelCatalog.class);
                    when(catalog.collectionState()).thenReturn(new PetTravelCatalog.CollectionState("HIDDEN",1,null,null));
                    return catalog;
                }).run(context->{
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PetTravelService.class).regions().items()).isEmpty();
                    assertThat(context).doesNotHaveBean(TourApiClient.class).doesNotHaveBean(org.redisson.api.RedissonClient.class);
                });
    }
    @Test void givenBudgetAboveCeiling__whenConfigured__thenReject() {
        for (int limit : new int[]{0,1001}) {
            assertThatThrownBy(()->new TourApiProperties().setDailyRequestLimit(limit)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Configuration(proxyBeanMethods=false)
    @Import({PetTravelController.class,PetTravelService.class})
    static class PublicConfig {}
}
