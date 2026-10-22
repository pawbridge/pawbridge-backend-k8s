package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications;
import com.pawbridge.backend.user.internal.password.infrastructure.BcryptPasswordHashes;
import com.pawbridge.backend.user.internal.passwordreset.application.PasswordReset;
import com.pawbridge.backend.user.internal.passwordreset.infrastructure.JdbcPasswordResetAccounts;
import com.pawbridge.backend.user.internal.passwordreset.web.PasswordResetController;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Dormant original LOCAL password reset. It requires the e-mail verification feature and fails without it. */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@ConditionalOnProperty(prefix = "pawbridge.password-reset", name = "enabled", havingValue = "true",
        matchIfMissing = false)
public class PasswordResetConfiguration {
    @Bean
    PasswordReset passwordReset(EmailVerifications verifications, @Qualifier("writeJdbc") JdbcTemplate jdbc) {
        return new PasswordReset(new JdbcPasswordResetAccounts(jdbc), verifications, new BcryptPasswordHashes());
    }

    @Bean
    PasswordResetController passwordResetController(PasswordReset resets) {
        return new PasswordResetController(resets);
    }
}
