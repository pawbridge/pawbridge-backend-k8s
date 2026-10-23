package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications;
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameStore;
import com.pawbridge.backend.user.internal.oauth.infrastructure.SocialNicknameGenerator;
import com.pawbridge.backend.user.internal.password.infrastructure.BcryptPasswordHashes;
import com.pawbridge.backend.user.internal.signup.application.LocalSignup;
import com.pawbridge.backend.user.internal.signup.infrastructure.JdbcSignupAccounts;
import com.pawbridge.backend.user.internal.signup.web.LocalSignupController;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Dormant original LOCAL signup. It requires the e-mail verification feature and fails without it. */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@ConditionalOnProperty(prefix = "pawbridge.local-signup", name = "enabled", havingValue = "true",
        matchIfMissing = false)
public class LocalSignupConfiguration {
    @Bean
    LocalSignup localSignup(EmailVerifications verifications, @Qualifier("writeJdbc") JdbcTemplate jdbc) {
        var nicknames = new SocialNicknameGenerator(new JdbcUserNicknameStore(jdbc));
        // Kept local to this feature: no global PasswordEncoder bean is registered.
        return new LocalSignup(verifications, new JdbcSignupAccounts(jdbc, nicknames::generateUniqueNickname),
                new BcryptPasswordHashes());
    }

    @Bean
    LocalSignupController localSignupController(LocalSignup signups) {
        return new LocalSignupController(signups);
    }
}
