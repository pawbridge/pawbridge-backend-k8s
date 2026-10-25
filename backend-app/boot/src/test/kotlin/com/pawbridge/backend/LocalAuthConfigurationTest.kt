package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.EmailVerificationConfiguration
import com.pawbridge.backend.boot.configuration.LocalSignupConfiguration
import com.pawbridge.backend.boot.configuration.PasswordResetConfiguration
import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications
import com.pawbridge.backend.user.internal.emailverification.application.VerificationMails
import com.pawbridge.backend.user.internal.emailverification.application.VerificationStore
import com.pawbridge.backend.user.internal.emailverification.infrastructure.RedisVerificationStore
import com.pawbridge.backend.user.internal.emailverification.infrastructure.SmtpVerificationMails
import com.pawbridge.backend.user.internal.emailverification.web.EmailVerificationController
import com.pawbridge.backend.user.internal.emailverification.web.LocalAuthExceptionHandler
import com.pawbridge.backend.user.internal.passwordreset.application.PasswordReset
import com.pawbridge.backend.user.internal.passwordreset.web.PasswordResetController
import com.pawbridge.backend.user.internal.signup.application.LocalSignup
import com.pawbridge.backend.user.internal.signup.web.LocalSignupController
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.springframework.aop.support.AopUtils
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Import
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.function.Supplier

class LocalAuthConfigurationTest {
    private fun runner(): ApplicationContextRunner {
        // Strict placeholder resolution, as in the real application, so missing inputs fail instead of staying literal.
        return ApplicationContextRunner()
            .withBean(PropertySourcesPlaceholderConfigurer::class.java, Supplier { PropertySourcesPlaceholderConfigurer() })
            .withUserConfiguration(
                EmailVerificationConfiguration::class.java,
                LocalSignupConfiguration::class.java,
                PasswordResetConfiguration::class.java,
            )
    }

    private fun enabled(): ApplicationContextRunner {
        return runner().withPropertyValues(*VERIFICATION_INPUTS)
            .withBean(JavaMailSender::class.java, Supplier { mock(JavaMailSender::class.java) })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { mock(JdbcTemplate::class.java) })
            .withBean(
                "writeTransactionManager",
                JdbcTransactionManager::class.java,
                Supplier { mock(JdbcTransactionManager::class.java) },
            )
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "on", "yes"])
    fun given__flags_not_exactly_true__when__assembling_without_inputs__then__register_nothing(flag: String) {
        var runner = runner()
        if (flag != "absent") {
            runner = runner.withPropertyValues(
                "pawbridge.email-verification.enabled=$flag",
                "pawbridge.local-signup.enabled=$flag",
                "pawbridge.password-reset.enabled=$flag",
            )
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(EmailVerifications::class.java)
                .doesNotHaveBean(VerificationStore::class.java)
                .doesNotHaveBean(VerificationMails::class.java)
                .doesNotHaveBean(EmailVerificationController::class.java)
                .doesNotHaveBean(LocalAuthExceptionHandler::class.java)
                .doesNotHaveBean(LocalSignup::class.java)
                .doesNotHaveBean(LocalSignupController::class.java)
                .doesNotHaveBean(PasswordReset::class.java)
                .doesNotHaveBean(PasswordResetController::class.java)
        }
    }

    @Test
    fun given__verification_enabled__when__assembling__then__redis_store_smtp_mails_service_controller_and_advice() {
        enabled().run { context ->
            assertThat(context).hasNotFailed()
                .hasSingleBean(EmailVerifications::class.java)
                .hasSingleBean(EmailVerificationController::class.java)
                .hasSingleBean(LocalAuthExceptionHandler::class.java)
                .doesNotHaveBean(LocalSignup::class.java)
                .doesNotHaveBean(PasswordReset::class.java)
                .doesNotHaveBean(PasswordEncoder::class.java)
            assertThat(context.getBean(VerificationStore::class.java)).isInstanceOf(RedisVerificationStore::class.java)
            assertThat(context.getBean(VerificationMails::class.java)).isInstanceOf(SmtpVerificationMails::class.java)
        }
    }

    @Test
    fun given__verification_enabled__when__another_unqualified_redis_factory_exists__then__it_stays_the_only_default_candidate() {
        enabled()
            .withBean(
                "otherRedisConnectionFactory",
                RedisConnectionFactory::class.java,
                Supplier { mock(RedisConnectionFactory::class.java) },
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(RedisConnectionFactory::class.java))
                    .isSameAs(context.getBean("otherRedisConnectionFactory"))
            }
    }

    @Test
    fun given__verification_enabled_without_redis_host_or_mail_sender_or_sender_address__when__assembling__then__fail() {
        runner().withPropertyValues("pawbridge.email-verification.enabled=true", "spring.mail.username=noreply@example.test")
            .withBean(JavaMailSender::class.java, Supplier { mock(JavaMailSender::class.java) })
            .run { context -> assertThat(context).hasFailed() }
        runner().withPropertyValues(*VERIFICATION_INPUTS).run { context -> assertThat(context).hasFailed() }
        runner().withPropertyValues("pawbridge.email-verification.enabled=true", "spring.data.redis.host=127.0.0.1")
            .withBean(JavaMailSender::class.java, Supplier { mock(JavaMailSender::class.java) })
            .run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun given__signup_without_verification_or_writer__when__assembling__then__fail_instead_of_creating_replacements() {
        runner().withPropertyValues("pawbridge.local-signup.enabled=true")
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { mock(JdbcTemplate::class.java) })
            .run { context -> assertThat(context).hasFailed() }
        runner().withPropertyValues(*VERIFICATION_INPUTS).withPropertyValues("pawbridge.local-signup.enabled=true")
            .withBean(JavaMailSender::class.java, Supplier { mock(JavaMailSender::class.java) })
            .run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun given__signup_and_reset_enabled__when__assembling__then__transactional_proxies_without_global_encoder() {
        enabled().withPropertyValues("pawbridge.local-signup.enabled=TRUE", "pawbridge.password-reset.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                    .hasSingleBean(LocalSignupController::class.java)
                    .hasSingleBean(PasswordResetController::class.java)
                    .doesNotHaveBean(PasswordEncoder::class.java)
                assertThat(AopUtils.isAopProxy(context.getBean(LocalSignup::class.java))).isTrue()
                assertThat(AopUtils.getTargetClass(context.getBean(LocalSignup::class.java))).isEqualTo(LocalSignup::class.java)
                assertThat(AopUtils.isAopProxy(context.getBean(PasswordReset::class.java))).isTrue()
                assertThat(AopUtils.getTargetClass(context.getBean(PasswordReset::class.java))).isEqualTo(PasswordReset::class.java)
            }
    }

    @Test
    fun given__application__when__inspecting_imports__then__each_local_auth_configuration_is_imported_once() {
        val imports = PawBridgeApplication::class.java.getAnnotation(Import::class.java).value.map { it.java }
        val types = arrayOf<Class<*>>(
            EmailVerificationConfiguration::class.java,
            LocalSignupConfiguration::class.java,
            PasswordResetConfiguration::class.java,
        )
        for (type in types) {
            assertThat(imports.count { candidate -> candidate == type }).isEqualTo(1)
        }
    }

    companion object {
        private val VERIFICATION_INPUTS = arrayOf(
            "pawbridge.email-verification.enabled=true",
            "spring.data.redis.host=127.0.0.1",
            "spring.mail.username=noreply@example.test",
        )
    }
}
