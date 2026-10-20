package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.directory.application.UserDirectoryQuery;
import com.pawbridge.backend.user.internal.directory.infrastructure.JdbcUserDirectoryQuery;
import com.pawbridge.backend.user.internal.directory.web.AdminUserDirectoryController;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
public class AdminUserDirectoryConfiguration {
    @Bean UserDirectoryQuery userDirectoryQuery(@Qualifier("readJdbc") JdbcTemplate jdbc,
            @Qualifier("readOnlyTransaction") TransactionTemplate transaction) {
        return new JdbcUserDirectoryQuery(jdbc,transaction);
    }
    @Bean AdminUserDirectoryController adminUserDirectoryController(UserDirectoryQuery users) {
        return new AdminUserDirectoryController(users);
    }
}
