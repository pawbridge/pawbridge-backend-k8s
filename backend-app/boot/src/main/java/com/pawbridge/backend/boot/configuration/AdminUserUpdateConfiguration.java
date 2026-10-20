package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate;
import com.pawbridge.backend.user.internal.adminupdate.infrastructure.JdbcAdminUserUpdate;
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateController;
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateExceptionHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class AdminUserUpdateConfiguration {
    @Bean
    AdminUserUpdate adminUserUpdate(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        return new JdbcAdminUserUpdate(jdbc, transaction);
    }

    @Bean
    AdminUserUpdateController adminUserUpdateController(AdminUserUpdate members) { return new AdminUserUpdateController(members); }

    @Bean
    AdminUserUpdateExceptionHandler adminUserUpdateExceptionHandler() { return new AdminUserUpdateExceptionHandler(); }
}
