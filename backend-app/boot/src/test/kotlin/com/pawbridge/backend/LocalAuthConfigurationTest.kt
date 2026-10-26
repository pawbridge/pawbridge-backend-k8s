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
import io.mockk.justRun
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.aop.support.AopUtils
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.function.Supplier

class LocalAuthConfigurationTest {
    // Spring runs the InitializingBean callback when it registers these as beans, so a strict mock must accept it.
    private fun jdbcTemplate(): JdbcTemplate {
        val bean = mockk<JdbcTemplate>()
        justRun { bean.afterPropertiesSet() }
        return bean
    }

    // Spring runs the InitializingBean callback when it registers these as beans, so a strict mock must accept it.
    private fun transactionManager(): JdbcTransactionManager {
        val bean = mockk<JdbcTransactionManager>()
        justRun { bean.afterPropertiesSet() }
        return bean
    }

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
            .withBean(JavaMailSender::class.java, Supplier { mockk<JavaMailSender>() })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbcTemplate() })
            .withBean(
                "writeTransactionManager",
                JdbcTransactionManager::class.java,
                Supplier { transactionManager() },
            )
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "on", "yes"])
    fun `플래그가 정확히 true가 아닐 때__입력 없이 조립하면__아무것도 등록하지 않는다`(flag: String) {
        // given
        var runner = runner()
        if (flag != "absent") {
            runner = runner.withPropertyValues(
                "pawbridge.email-verification.enabled=$flag",
                "pawbridge.local-signup.enabled=$flag",
                "pawbridge.password-reset.enabled=$flag",
            )
        }

        // when
        runner.run { context ->
            // then
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
    fun `인증이 켜졌을 때__조립하면__Redis 저장소, SMTP 메일, 서비스, 컨트롤러, 어드바이스를 등록한다`() {
        // given
        val runner = enabled()

        // when
        runner.run { context ->
            // then
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
    fun `인증이 켜졌을 때__한정자 없는 다른 Redis 팩토리가 있으면__그것이 유일한 기본 후보로 남는다`() {
        // given
        val runner = enabled()
            .withBean(
                "otherRedisConnectionFactory",
                RedisConnectionFactory::class.java,
                Supplier { mockk<RedisConnectionFactory>() },
            )

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(RedisConnectionFactory::class.java))
                .isSameAs(context.getBean("otherRedisConnectionFactory"))
        }
    }

    @Test
    fun `Redis 호스트나 메일 발송기나 발신 주소가 없을 때__인증을 켜고 조립하면__실패한다`() {
        // given
        val withoutRedisHost = runner()
            .withPropertyValues("pawbridge.email-verification.enabled=true", "spring.mail.username=noreply@example.test")
            .withBean(JavaMailSender::class.java, Supplier { mockk<JavaMailSender>() })
        val withoutMailSender = runner().withPropertyValues(*VERIFICATION_INPUTS)
        val withoutSenderAddress = runner()
            .withPropertyValues("pawbridge.email-verification.enabled=true", "spring.data.redis.host=127.0.0.1")
            .withBean(JavaMailSender::class.java, Supplier { mockk<JavaMailSender>() })

        // when
        // then
        withoutRedisHost.run { context -> assertThat(context).hasFailed() }
        withoutMailSender.run { context -> assertThat(context).hasFailed() }
        withoutSenderAddress.run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `인증이나 쓰기 접근이 없을 때__가입을 켜고 조립하면__대체물을 만들지 않고 실패한다`() {
        // given
        val withoutVerification = runner()
            .withPropertyValues("pawbridge.local-signup.enabled=true")
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbcTemplate() })
        val withoutWriter = runner()
            .withPropertyValues(*VERIFICATION_INPUTS)
            .withPropertyValues("pawbridge.local-signup.enabled=true")
            .withBean(JavaMailSender::class.java, Supplier { mockk<JavaMailSender>() })

        // when
        // then
        withoutVerification.run { context -> assertThat(context).hasFailed() }
        withoutWriter.run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `가입과 재설정이 켜졌을 때__조립하면__전역 인코더 없이 트랜잭션 프록시가 된다`() {
        // given
        val runner = enabled()
            .withPropertyValues("pawbridge.local-signup.enabled=TRUE", "pawbridge.password-reset.enabled=true")

        // when
        runner.run { context ->
            // then
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

    companion object {
        private val VERIFICATION_INPUTS = arrayOf(
            "pawbridge.email-verification.enabled=true",
            "spring.data.redis.host=127.0.0.1",
            "spring.mail.username=noreply@example.test",
        )
    }
}
