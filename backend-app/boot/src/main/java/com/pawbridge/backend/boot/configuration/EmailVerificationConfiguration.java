package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications;
import com.pawbridge.backend.user.internal.emailverification.application.VerificationMails;
import com.pawbridge.backend.user.internal.emailverification.application.VerificationStore;
import com.pawbridge.backend.user.internal.emailverification.infrastructure.RedisVerificationStore;
import com.pawbridge.backend.user.internal.emailverification.infrastructure.SmtpVerificationMails;
import com.pawbridge.backend.user.internal.emailverification.web.EmailVerificationController;
import com.pawbridge.backend.user.internal.emailverification.web.LocalAuthExceptionHandler;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Dormant e-mail verification: the original send/verify endpoints, Redis state and SMTP sender. It also
 * provides the shared service behind the separately flagged signup and password-reset routes.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.email-verification", name = "enabled", havingValue = "true",
        matchIfMissing = false)
public class EmailVerificationConfiguration {
    @Bean
    LettuceConnectionFactory localAuthRedisConnectionFactory(@Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port:6379}") int port, @Value("${spring.data.redis.database:0}") int database,
            @Value("${spring.data.redis.username:}") String username,
            @Value("${spring.data.redis.password:}") String password) {
        if (host.isBlank() || port < 1 || port > 65535 || database < 0) {
            throw new IllegalArgumentException("Explicit verification Redis settings required");
        }
        var server = new RedisStandaloneConfiguration(host, port);
        server.setDatabase(database);
        if (!username.isBlank()) {
            server.setUsername(username);
        }
        if (!password.isEmpty()) {
            server.setPassword(RedisPassword.of(password));
        }
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2))
                .shutdownTimeout(Duration.ofSeconds(2))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build()).build())
                .build();
        return new LettuceConnectionFactory(server, client);
    }

    @Bean
    StringRedisTemplate localAuthRedis(
            @Qualifier("localAuthRedisConnectionFactory") LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    VerificationStore localAuthVerificationStore(@Qualifier("localAuthRedis") StringRedisTemplate redis) {
        return new RedisVerificationStore(redis);
    }

    @Bean
    VerificationMails localAuthVerificationMails(JavaMailSender mailSender,
            @Value("${spring.mail.username}") String fromEmail) {
        return new SmtpVerificationMails(mailSender, fromEmail);
    }

    @Bean
    EmailVerifications emailVerifications(VerificationStore store, VerificationMails mails) {
        return new EmailVerifications(store, mails);
    }

    @Bean
    EmailVerificationController emailVerificationController(EmailVerifications verifications) {
        return new EmailVerificationController(verifications);
    }

    @Bean
    LocalAuthExceptionHandler localAuthExceptionHandler() {
        return new LocalAuthExceptionHandler();
    }
}
