package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.UserPasswordConfiguration
import com.pawbridge.backend.user.internal.password.application.ChangeUserPassword
import com.pawbridge.backend.user.internal.password.application.UserPasswordStore
import com.pawbridge.backend.user.internal.password.web.UserPasswordController
import com.pawbridge.backend.user.internal.password.web.UserPasswordExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.springframework.aop.support.AopUtils
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.util.ReflectionTestUtils
import java.util.function.Supplier

class UserPasswordConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "on", "yes"])
    fun given__flag_not_exactly_true__when__assembling_without_inputs__then__register_no_password_feature(
        flag: String,
    ) {
        var runner = ApplicationContextRunner().withUserConfiguration(UserPasswordConfiguration::class.java)
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.user-password.enabled=$flag")
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(ChangeUserPassword::class.java)
                .doesNotHaveBean(UserPasswordStore::class.java)
                .doesNotHaveBean(UserPasswordController::class.java)
                .doesNotHaveBean(UserPasswordExceptionHandler::class.java)
        }
    }

    @Test
    fun given__enabled_without_writer__when__assembling__then__fail_instead_of_creating_a_replacement() {
        ApplicationContextRunner().withUserConfiguration(UserPasswordConfiguration::class.java)
            .withPropertyValues("pawbridge.user-password.enabled=true")
            .run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun given__enabled_writer__when__assembling__then__reuse_writer_jdbc_wrap_change_in_transaction_and_register_no_global_encoder() {
        ApplicationContextRunner().withUserConfiguration(UserPasswordConfiguration::class.java)
            .withPropertyValues("pawbridge.user-password.enabled=TRUE")
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { mock(JdbcTemplate::class.java) })
            .withBean(
                "writeTransactionManager",
                JdbcTransactionManager::class.java,
                Supplier { mock(JdbcTransactionManager::class.java) },
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                    .hasSingleBean(UserPasswordController::class.java)
                    .hasSingleBean(UserPasswordExceptionHandler::class.java)
                    .doesNotHaveBean(PasswordEncoder::class.java)
                assertThat(ReflectionTestUtils.getField(context.getBean(UserPasswordStore::class.java), "jdbc"))
                    .isSameAs(context.getBean("writeJdbc"))
                val change = context.getBean(ChangeUserPassword::class.java)
                assertThat(AopUtils.isAopProxy(change)).isTrue()
                assertThat(AopUtils.getTargetClass(change)).isEqualTo(ChangeUserPassword::class.java)
            }
    }

    @Test
    fun given__application__when__inspecting_imports__then__password_configuration_is_imported_once() {
        assertThat(
            PawBridgeApplication::class.java.getAnnotation(Import::class.java).value
                .filter { type -> type.java == UserPasswordConfiguration::class.java }
                .size,
        ).isEqualTo(1)
    }
}
