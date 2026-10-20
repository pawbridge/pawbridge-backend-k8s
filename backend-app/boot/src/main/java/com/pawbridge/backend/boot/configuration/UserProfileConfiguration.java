package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery;
import com.pawbridge.backend.user.internal.profile.infrastructure.JdbcUserProfileQuery;
import com.pawbridge.backend.user.internal.profile.web.UserProfileController;
import com.pawbridge.backend.user.internal.profile.web.AdminUserDetailController;
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class UserProfileConfiguration {
    @Bean
    UserProfileQuery userProfileQuery(@Qualifier("readJdbc") JdbcTemplate jdbc,
                                      @Qualifier("readOnlyTransaction") TransactionTemplate transaction) {
        return new JdbcUserProfileQuery(jdbc, transaction);
    }

    @Bean
    UserProfileController userProfileController(UserProfileQuery profiles) { return new UserProfileController(profiles); }

    @Bean
    AdminUserDetailController adminUserDetailController(UserProfileQuery profiles) {
        return new AdminUserDetailController(profiles);
    }

    @Bean
    UserProfileExceptionHandler userProfileExceptionHandler() { return new UserProfileExceptionHandler(); }
}
