package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate
import com.pawbridge.backend.user.internal.adminupdate.infrastructure.JdbcAdminUserUpdate
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateController
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateExceptionHandler
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import java.time.LocalDateTime
import java.util.function.Supplier
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class AdminUserUpdateConfiguration {
    /** With auth sessions disabled there are no refreshable sessions to revoke on a demotion. */
    @Bean
    fun adminUserUpdate(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
        sessions: ObjectProvider<UserSessionRevoker>,
    ): AdminUserUpdate {
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 15
        val revoker: UserSessionRevoker = sessions.getIfAvailable { UserSessionRevoker.NONE }
        return JdbcAdminUserUpdate(jdbc, transaction, Supplier { LocalDateTime.now() }, revoker)
    }

    @Bean
    fun adminUserUpdateController(members: AdminUserUpdate): AdminUserUpdateController {
        return AdminUserUpdateController(members)
    }

    @Bean
    fun adminUserUpdateExceptionHandler(): AdminUserUpdateExceptionHandler {
        return AdminUserUpdateExceptionHandler()
    }
}
