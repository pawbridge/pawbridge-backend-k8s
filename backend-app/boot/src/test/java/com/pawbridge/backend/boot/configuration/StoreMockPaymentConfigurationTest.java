package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.pawbridge.backend.store.api.StorePaymentEvents;
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentController;
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StoreMockPaymentConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withUserConfiguration(StoreMockPaymentConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings={"absent","false","yes","on","1","unknown"})
    void given__mock_payment_not_explicitly_true__when__assembling_without_payment_business__then__create_no_route_beans(String flag) {
        var selected=flag.equals("absent")?runner:runner.withPropertyValues("pawbridge.store-mock-payment.enabled="+flag);
        selected.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(MockOrderPaymentController.class).doesNotHaveBean(MockOrderPaymentExceptionHandler.class));
    }

    @Test
    void given__mock_payment_true_and_existing_payment_business__when__assembling__then__route_to_that_single_business() {
        StorePaymentEvents payments=mock(StorePaymentEvents.class);
        runner.withPropertyValues("pawbridge.store-mock-payment.enabled=TRUE")
                .withBean("storePaymentEvents",StorePaymentEvents.class,() -> payments)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MockOrderPaymentController.class)
                            .hasSingleBean(MockOrderPaymentExceptionHandler.class).hasSingleBean(StorePaymentEvents.class);
                    context.getBean(MockOrderPaymentController.class).processPayment(12L);
                    verify(payments).processPayment(12L);
                });
    }

    @Test
    void given__mock_payment_true_without_store_payment_business__when__assembling__then__fail_fast() {
        runner.withPropertyValues("pawbridge.store-mock-payment.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isInstanceOf(UnsatisfiedDependencyException.class)
                    .hasMessageContaining("StorePaymentEvents");
        });
    }
}
