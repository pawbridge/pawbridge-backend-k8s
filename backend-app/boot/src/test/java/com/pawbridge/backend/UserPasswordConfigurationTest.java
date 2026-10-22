package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.pawbridge.backend.boot.configuration.UserPasswordConfiguration;
import com.pawbridge.backend.user.internal.password.application.ChangeUserPassword;
import com.pawbridge.backend.user.internal.password.application.UserPasswordStore;
import com.pawbridge.backend.user.internal.password.web.UserPasswordController;
import com.pawbridge.backend.user.internal.password.web.UserPasswordExceptionHandler;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class UserPasswordConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "on", "yes"})
    void given__flag_not_exactly_true__when__assembling_without_inputs__then__register_no_password_feature(String flag) {
        var runner = new ApplicationContextRunner().withUserConfiguration(UserPasswordConfiguration.class);
        if (!flag.equals("absent")) runner = runner.withPropertyValues("pawbridge.user-password.enabled=" + flag);
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ChangeUserPassword.class)
                .doesNotHaveBean(UserPasswordStore.class).doesNotHaveBean(UserPasswordController.class)
                .doesNotHaveBean(UserPasswordExceptionHandler.class));
    }

    @Test
    void given__enabled_without_writer__when__assembling__then__fail_instead_of_creating_a_replacement() {
        new ApplicationContextRunner().withUserConfiguration(UserPasswordConfiguration.class)
                .withPropertyValues("pawbridge.user-password.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void given__enabled_writer__when__assembling__then__reuse_writer_jdbc_wrap_change_in_transaction_and_register_no_global_encoder() {
        new ApplicationContextRunner().withUserConfiguration(UserPasswordConfiguration.class)
                .withPropertyValues("pawbridge.user-password.enabled=TRUE")
                .withBean("writeJdbc", JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean("writeTransactionManager", JdbcTransactionManager.class, () -> mock(JdbcTransactionManager.class))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(UserPasswordController.class)
                            .hasSingleBean(UserPasswordExceptionHandler.class).doesNotHaveBean(PasswordEncoder.class);
                    assertThat(ReflectionTestUtils.getField(context.getBean(UserPasswordStore.class), "jdbc"))
                            .isSameAs(context.getBean("writeJdbc"));
                    var change = context.getBean(ChangeUserPassword.class);
                    assertThat(AopUtils.isAopProxy(change)).isTrue();
                    assertThat(AopUtils.getTargetClass(change)).isEqualTo(ChangeUserPassword.class);
                });
    }

    @Test
    void given__application__when__inspecting_imports__then__password_configuration_is_imported_once() {
        assertThat(Arrays.stream(PawBridgeApplication.class.getAnnotation(Import.class).value())
                .filter(type -> type == UserPasswordConfiguration.class).count()).isEqualTo(1);
    }
}
