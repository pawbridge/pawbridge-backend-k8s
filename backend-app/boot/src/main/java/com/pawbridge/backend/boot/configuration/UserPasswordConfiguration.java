package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.password.application.ChangeUserPassword;
import com.pawbridge.backend.user.internal.password.application.PasswordHashes;
import com.pawbridge.backend.user.internal.password.application.UserPasswordStore;
import com.pawbridge.backend.user.internal.password.infrastructure.BcryptPasswordHashes;
import com.pawbridge.backend.user.internal.password.infrastructure.JdbcUserPasswordStore;
import com.pawbridge.backend.user.internal.password.web.UserPasswordController;
import com.pawbridge.backend.user.internal.password.web.UserPasswordExceptionHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@ConditionalOnProperty(prefix = "pawbridge.user-password", name = "enabled", havingValue = "true", matchIfMissing = false)
public class UserPasswordConfiguration {
    @Bean
    UserPasswordStore userPasswordStore(@Qualifier("writeJdbc") JdbcTemplate jdbc) {
        return new JdbcUserPasswordStore(jdbc);
    }

    // Kept local to this feature: no global PasswordEncoder bean is registered.
    @Bean
    ChangeUserPassword changeUserPassword(UserPasswordStore users) {
        PasswordHashes hashes = new BcryptPasswordHashes();
        return new ChangeUserPassword(users, hashes);
    }

    @Bean
    UserPasswordController userPasswordController(ChangeUserPassword change) { return new UserPasswordController(change); }

    @Bean
    UserPasswordExceptionHandler userPasswordExceptionHandler() { return new UserPasswordExceptionHandler(); }
}
