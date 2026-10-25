package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.boot.javaIsBlank
import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications
import com.pawbridge.backend.user.internal.emailverification.application.VerificationMails
import com.pawbridge.backend.user.internal.emailverification.application.VerificationStore
import com.pawbridge.backend.user.internal.emailverification.infrastructure.RedisVerificationStore
import com.pawbridge.backend.user.internal.emailverification.infrastructure.SmtpVerificationMails
import com.pawbridge.backend.user.internal.emailverification.web.EmailVerificationController
import com.pawbridge.backend.user.internal.emailverification.web.LocalAuthExceptionHandler
import io.lettuce.core.ClientOptions
import io.lettuce.core.SocketOptions
import java.time.Duration
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.mail.javamail.JavaMailSender

/**
 * Dormant e-mail verification: the original send/verify endpoints, Redis state and SMTP sender. It also
 * provides the shared service behind the separately flagged signup and password-reset routes.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "pawbridge.email-verification",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class EmailVerificationConfiguration {
    // Not a default autowire candidate: member chat injects an unqualified RedisConnectionFactory.
    @Bean(defaultCandidate = false)
    fun localAuthRedisConnectionFactory(
        @Value("\${spring.data.redis.host}") host: String,
        @Value("\${spring.data.redis.port:6379}") port: Int,
        @Value("\${spring.data.redis.database:0}") database: Int,
        @Value("\${spring.data.redis.username:}") username: String,
        @Value("\${spring.data.redis.password:}") password: String,
    ): LettuceConnectionFactory {
        if (javaIsBlank(host) || port < 1 || port > 65535 || database < 0) {
            throw IllegalArgumentException("Explicit verification Redis settings required")
        }
        val server = RedisStandaloneConfiguration(host, port)
        server.database = database
        if (!javaIsBlank(username)) {
            server.username = username
        }
        if (password.isNotEmpty()) {
            server.password = RedisPassword.of(password)
        }
        val client = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(2))
            .shutdownTimeout(Duration.ofSeconds(2))
            .clientOptions(
                ClientOptions.builder()
                    .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build())
                    .build(),
            )
            .build()
        return LettuceConnectionFactory(server, client)
    }

    @Bean
    fun localAuthRedis(
        @Qualifier("localAuthRedisConnectionFactory") factory: LettuceConnectionFactory,
    ): StringRedisTemplate {
        return StringRedisTemplate(factory)
    }

    @Bean
    fun localAuthVerificationStore(@Qualifier("localAuthRedis") redis: StringRedisTemplate): VerificationStore {
        return RedisVerificationStore(redis)
    }

    @Bean
    fun localAuthVerificationMails(
        mailSender: JavaMailSender,
        @Value("\${spring.mail.username}") fromEmail: String,
    ): VerificationMails {
        return SmtpVerificationMails(mailSender, fromEmail)
    }

    @Bean
    fun emailVerifications(store: VerificationStore, mails: VerificationMails): EmailVerifications {
        return EmailVerifications(store, mails)
    }

    @Bean
    fun emailVerificationController(verifications: EmailVerifications): EmailVerificationController {
        return EmailVerificationController(verifications)
    }

    @Bean
    fun localAuthExceptionHandler(): LocalAuthExceptionHandler {
        return LocalAuthExceptionHandler()
    }
}
