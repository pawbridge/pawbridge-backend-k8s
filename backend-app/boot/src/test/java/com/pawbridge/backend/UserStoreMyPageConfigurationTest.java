package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.boot.configuration.UserStoreMyPageConfiguration;
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery;
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage;
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageController;
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageExceptionHandler;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

class UserStoreMyPageConfigurationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(UserStoreMyPageConfiguration.class, Inputs.class);
    }

    @Test
    void given__no_explicit_enablement__when__starting_with_no_store_address__then__create_no_client_or_routes() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(StoreMyPage.class)).isEmpty();
            assertThat(context.getBeansOfType(StoreMyPageController.class)).isEmpty();
            assertThat(context.getBeansOfType(StoreMyPageExceptionHandler.class)).isEmpty();
            assertThat(context.getBeansOfType(HttpClient.class)).isEmpty();
        });
    }

    @Test
    void given__explicit_enablement_and_store_origin__when__assembling__then__reuse_profile_and_read_transaction_without_connecting() {
        runner().withPropertyValues("pawbridge.user-store-mypage.enabled=true", "service.store.url=http://store.example.invalid:8080")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var service = context.getBean(StoreMyPage.class);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(service, "profiles"))
                            .isSameAs(context.getBean(UserProfileQuery.class));
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(service, "read"))
                            .isSameAs(context.getBean("readOnlyTransaction"));
                    assertThat(context.getBeansOfType(StoreMyPageController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(StoreMyPageExceptionHandler.class)).hasSize(1);
                    var client = context.getBean(HttpClient.class);
                    assertThat(client.connectTimeout()).contains(Duration.ofSeconds(10));
                    assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
                    verify(context.getBean(UserProfileQuery.class), never()).require(anyLong());
                    verify(context.getBean("readOnlyTransaction", TransactionTemplate.class), never()).execute(any());
                });
    }

    @ParameterizedTest @ValueSource(strings = {"", "/store", "ftp://store.example.invalid", "https://user:synthetic-private@store.example.invalid", "https://store.example.invalid/api", "https://store.example.invalid?key=synthetic-private", "https://store.example.invalid#synthetic-private", "not a synthetic-private URI"})
    void given__missing_or_unsafe_explicit_origin__when__enabling__then__reject_before_requests_without_echoing_the_value(String address) {
        runner().withPropertyValues("pawbridge.user-store-mypage.enabled=true", "service.store.url=" + address)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("An explicit Store HTTP origin is required");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class Inputs {
        @Bean UserProfileQuery userProfileQuery() { return mock(UserProfileQuery.class); }
        @Bean TransactionTemplate readOnlyTransaction() { return mock(TransactionTemplate.class); }
    }
}
