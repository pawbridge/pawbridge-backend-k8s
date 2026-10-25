package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.user.internal.directory.application.UserDirectoryQuery
import com.pawbridge.backend.user.internal.directory.infrastructure.JdbcUserDirectoryQuery
import com.pawbridge.backend.user.internal.directory.web.AdminUserDirectoryController
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class AdminUserDirectoryConfiguration {
    @Bean
    fun userDirectoryQuery(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") transaction: TransactionTemplate,
    ): UserDirectoryQuery {
        return JdbcUserDirectoryQuery(jdbc, transaction)
    }

    @Bean
    fun adminUserDirectoryController(users: UserDirectoryQuery): AdminUserDirectoryController {
        return AdminUserDirectoryController(users)
    }
}
