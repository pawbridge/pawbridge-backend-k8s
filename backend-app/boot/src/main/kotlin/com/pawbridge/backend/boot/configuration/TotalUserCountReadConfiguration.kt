package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery
import com.pawbridge.backend.user.internal.statistics.infrastructure.JdbcTotalUserCountQuery
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class TotalUserCountReadConfiguration {
    @Bean
    fun totalUserCountQuery(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") transaction: TransactionTemplate,
    ): TotalUserCountQuery {
        return JdbcTotalUserCountQuery(jdbc, transaction)
    }

    @Bean
    fun totalUserCountController(users: TotalUserCountQuery): TotalUserCountController {
        return TotalUserCountController(users)
    }

    @Bean
    fun totalUserCountExceptionHandler(): TotalUserCountExceptionHandler {
        return TotalUserCountExceptionHandler()
    }
}
