package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate;
import com.pawbridge.backend.user.internal.adminupdate.infrastructure.JdbcAdminUserUpdate;
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateController;
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateExceptionHandler;
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker;
import java.time.LocalDateTime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class AdminUserUpdateConfiguration {
    /** With auth sessions disabled there are no refreshable sessions to revoke on a demotion. */
    @Bean
    AdminUserUpdate adminUserUpdate(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager,
            ObjectProvider<UserSessionRevoker> sessions) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        UserSessionRevoker revoker = sessions.getIfAvailable(() -> UserSessionRevoker.NONE);
        return new JdbcAdminUserUpdate(jdbc, transaction, LocalDateTime::now, revoker);
    }

    @Bean
    AdminUserUpdateController adminUserUpdateController(AdminUserUpdate members) { return new AdminUserUpdateController(members); }

    @Bean
    AdminUserUpdateExceptionHandler adminUserUpdateExceptionHandler() { return new AdminUserUpdateExceptionHandler(); }
}
