package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.store.api.StorePaymentEvents
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentController
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentExceptionHandler
import com.pawbridge.backend.support.javaLong
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.beans.factory.UnsatisfiedDependencyException
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.function.Supplier

class StoreMockPaymentConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withUserConfiguration(StoreMockPaymentConfiguration::class.java)

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes", "on", "1", "unknown"])
    fun given__mock_payment_not_explicitly_true__when__assembling_without_payment_business__then__create_no_route_beans(
        flag: String,
    ) {
        val selected = if (flag == "absent") {
            runner
        } else {
            runner.withPropertyValues("pawbridge.store-mock-payment.enabled=$flag")
        }
        selected.run { context ->
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(MockOrderPaymentController::class.java)
                .doesNotHaveBean(MockOrderPaymentExceptionHandler::class.java)
        }
    }

    @Test
    fun given__mock_payment_true_and_existing_payment_business__when__assembling__then__route_to_that_single_business() {
        val payments: StorePaymentEvents = mock(StorePaymentEvents::class.java)
        runner.withPropertyValues("pawbridge.store-mock-payment.enabled=TRUE")
            .withBean("storePaymentEvents", StorePaymentEvents::class.java, Supplier { payments })
            .run { context ->
                assertThat(context).hasNotFailed()
                    .hasSingleBean(MockOrderPaymentController::class.java)
                    .hasSingleBean(MockOrderPaymentExceptionHandler::class.java)
                    .hasSingleBean(StorePaymentEvents::class.java)
                context.getBean(MockOrderPaymentController::class.java).processPayment(javaLong(12L))
                verify(payments).processPayment(12L)
            }
    }

    @Test
    fun given__mock_payment_true_without_store_payment_business__when__assembling__then__fail_fast() {
        runner.withPropertyValues("pawbridge.store-mock-payment.enabled=true").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).isInstanceOf(UnsatisfiedDependencyException::class.java)
                .hasMessageContaining("StorePaymentEvents")
        }
    }
}
