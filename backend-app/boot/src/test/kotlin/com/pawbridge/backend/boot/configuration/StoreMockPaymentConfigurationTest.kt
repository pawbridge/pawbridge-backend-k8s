package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.store.api.StorePaymentEvents
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentController
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentExceptionHandler
import com.pawbridge.backend.support.javaLong
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.UnsatisfiedDependencyException
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.function.Supplier

class StoreMockPaymentConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withUserConfiguration(StoreMockPaymentConfiguration::class.java)

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes", "on", "1", "unknown"])
    fun `모의 결제가 명시적 true가 아닐 때__결제 비즈니스 없이 조립하면__라우트 빈을 만들지 않는다`(
        flag: String,
    ) {
        // given
        val selected = if (flag == "absent") {
            runner
        } else {
            runner.withPropertyValues("pawbridge.store-mock-payment.enabled=$flag")
        }

        // when
        selected.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(MockOrderPaymentController::class.java)
                .doesNotHaveBean(MockOrderPaymentExceptionHandler::class.java)
        }
    }

    @Test
    fun `모의 결제가 true이고 결제 비즈니스가 있을 때__조립하면__그 비즈니스 하나로 라우팅한다`() {
        // given
        val payments = mockk<StorePaymentEvents>()

        justRun { payments.processPayment(any()) }

        // when
        runner.withPropertyValues("pawbridge.store-mock-payment.enabled=TRUE")
            .withBean("storePaymentEvents", StorePaymentEvents::class.java, Supplier { payments })
            .run { context ->
                // then
                assertThat(context).hasNotFailed()
                    .hasSingleBean(MockOrderPaymentController::class.java)
                    .hasSingleBean(MockOrderPaymentExceptionHandler::class.java)
                    .hasSingleBean(StorePaymentEvents::class.java)
                context.getBean(MockOrderPaymentController::class.java).processPayment(javaLong(12L))
                verify(exactly = 1) { payments.processPayment(12L) }
            }
    }

    @Test
    fun `모의 결제가 true인데 스토어 결제 비즈니스가 없을 때__조립하면__즉시 실패한다`() {
        // given
        val selected = runner.withPropertyValues("pawbridge.store-mock-payment.enabled=true")

        // when
        selected.run { context ->
            // then
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).isInstanceOf(UnsatisfiedDependencyException::class.java)
                .hasMessageContaining("StorePaymentEvents")
        }
    }
}
