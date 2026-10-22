package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.pawbridge.backend.boot.configuration.EmailVerificationConfiguration;
import com.pawbridge.backend.boot.configuration.LocalSignupConfiguration;
import com.pawbridge.backend.boot.configuration.PasswordResetConfiguration;
import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications;
import com.pawbridge.backend.user.internal.emailverification.application.VerificationMails;
import com.pawbridge.backend.user.internal.emailverification.application.VerificationStore;
import com.pawbridge.backend.user.internal.emailverification.infrastructure.RedisVerificationStore;
import com.pawbridge.backend.user.internal.emailverification.infrastructure.SmtpVerificationMails;
import com.pawbridge.backend.user.internal.emailverification.web.EmailVerificationController;
import com.pawbridge.backend.user.internal.emailverification.web.LocalAuthExceptionHandler;
import com.pawbridge.backend.user.internal.passwordreset.application.PasswordReset;
import com.pawbridge.backend.user.internal.passwordreset.web.PasswordResetController;
import com.pawbridge.backend.user.internal.signup.application.LocalSignup;
import com.pawbridge.backend.user.internal.signup.web.LocalSignupController;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;

class LocalAuthConfigurationTest {
    private static final String[] VERIFICATION_INPUTS = {"pawbridge.email-verification.enabled=true",
            "spring.data.redis.host=127.0.0.1", "spring.mail.username=noreply@example.test"};

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(EmailVerificationConfiguration.class, LocalSignupConfiguration.class,
                        PasswordResetConfiguration.class);
    }

    private ApplicationContextRunner enabled() {
        return runner().withPropertyValues(VERIFICATION_INPUTS)
                .withBean(JavaMailSender.class, () -> mock(JavaMailSender.class))
                .withBean("writeJdbc", JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean("writeTransactionManager", JdbcTransactionManager.class, () -> mock(JdbcTransactionManager.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "on", "yes"})
    void given__flags_not_exactly_true__when__assembling_without_inputs__then__register_nothing(String flag) {
        var runner = runner();
        if (!flag.equals("absent")) {
            runner = runner.withPropertyValues("pawbridge.email-verification.enabled=" + flag,
                    "pawbridge.local-signup.enabled=" + flag, "pawbridge.password-reset.enabled=" + flag);
        }
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(EmailVerifications.class)
                .doesNotHaveBean(VerificationStore.class).doesNotHaveBean(VerificationMails.class)
                .doesNotHaveBean(EmailVerificationController.class).doesNotHaveBean(LocalAuthExceptionHandler.class)
                .doesNotHaveBean(LocalSignup.class).doesNotHaveBean(LocalSignupController.class)
                .doesNotHaveBean(PasswordReset.class).doesNotHaveBean(PasswordResetController.class));
    }

    @Test
    void given__verification_enabled__when__assembling__then__redis_store_smtp_mails_service_controller_and_advice() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(EmailVerifications.class)
                    .hasSingleBean(EmailVerificationController.class).hasSingleBean(LocalAuthExceptionHandler.class)
                    .doesNotHaveBean(LocalSignup.class).doesNotHaveBean(PasswordReset.class)
                    .doesNotHaveBean(PasswordEncoder.class);
            assertThat(context.getBean(VerificationStore.class)).isInstanceOf(RedisVerificationStore.class);
            assertThat(context.getBean(VerificationMails.class)).isInstanceOf(SmtpVerificationMails.class);
        });
    }

    @Test
    void given__verification_enabled_without_redis_host_or_mail_sender_or_sender_address__when__assembling__then__fail() {
        runner().withPropertyValues("pawbridge.email-verification.enabled=true", "spring.mail.username=noreply@example.test")
                .withBean(JavaMailSender.class, () -> mock(JavaMailSender.class))
                .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues(VERIFICATION_INPUTS).run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues("pawbridge.email-verification.enabled=true", "spring.data.redis.host=127.0.0.1")
                .withBean(JavaMailSender.class, () -> mock(JavaMailSender.class))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void given__signup_without_verification_or_writer__when__assembling__then__fail_instead_of_creating_replacements() {
        runner().withPropertyValues("pawbridge.local-signup.enabled=true")
                .withBean("writeJdbc", JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues(VERIFICATION_INPUTS).withPropertyValues("pawbridge.local-signup.enabled=true")
                .withBean(JavaMailSender.class, () -> mock(JavaMailSender.class))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void given__signup_and_reset_enabled__when__assembling__then__transactional_proxies_without_global_encoder() {
        enabled().withPropertyValues("pawbridge.local-signup.enabled=TRUE", "pawbridge.password-reset.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(LocalSignupController.class)
                            .hasSingleBean(PasswordResetController.class).doesNotHaveBean(PasswordEncoder.class);
                    assertThat(AopUtils.isAopProxy(context.getBean(LocalSignup.class))).isTrue();
                    assertThat(AopUtils.getTargetClass(context.getBean(LocalSignup.class))).isEqualTo(LocalSignup.class);
                    assertThat(AopUtils.isAopProxy(context.getBean(PasswordReset.class))).isTrue();
                    assertThat(AopUtils.getTargetClass(context.getBean(PasswordReset.class))).isEqualTo(PasswordReset.class);
                });
    }

    @Test
    void given__application__when__inspecting_imports__then__each_local_auth_configuration_is_imported_once() {
        var imports = Arrays.asList(PawBridgeApplication.class.getAnnotation(Import.class).value());
        for (Class<?> type : new Class<?>[] {EmailVerificationConfiguration.class, LocalSignupConfiguration.class,
                PasswordResetConfiguration.class}) {
            assertThat(imports.stream().filter(candidate -> candidate == type).count()).isEqualTo(1);
        }
    }
}
