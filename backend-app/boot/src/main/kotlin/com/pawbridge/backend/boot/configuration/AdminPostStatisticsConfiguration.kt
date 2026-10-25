package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcAdminPostStatisticsQuery
import com.pawbridge.backend.community.internal.post.web.AdminPostStatisticsController
import com.pawbridge.backend.community.internal.post.web.AdminPostStatisticsExceptionHandler
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class AdminPostStatisticsConfiguration {
    @Bean
    fun adminPostStatisticsTransaction(
        @Qualifier("transactionManager") manager: JdbcTransactionManager,
    ): TransactionTemplate {
        val transaction = TransactionTemplate(manager)
        transaction.isReadOnly = true
        transaction.timeout = 3
        transaction.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        return transaction
    }

    @Bean
    fun adminPostStatisticsQuery(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") todayTransaction: TransactionTemplate,
        @Qualifier("adminPostStatisticsTransaction") periodTransaction: TransactionTemplate,
        @Qualifier("readClock") clock: Clock,
    ): AdminPostStatisticsQuery {
        return JdbcAdminPostStatisticsQuery(jdbc, todayTransaction, periodTransaction, clock)
    }

    @Bean
    fun adminPostStatisticsController(statistics: AdminPostStatisticsQuery): AdminPostStatisticsController {
        return AdminPostStatisticsController(statistics)
    }

    @Bean
    fun adminPostStatisticsExceptionHandler(): AdminPostStatisticsExceptionHandler {
        return AdminPostStatisticsExceptionHandler()
    }
}
