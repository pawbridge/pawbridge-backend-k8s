package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery
import com.pawbridge.backend.user.internal.statistics.infrastructure.JdbcSignupStatisticsQuery
import com.pawbridge.backend.user.internal.statistics.web.SignupStatisticsController
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class SignupStatisticsReadConfiguration {
    @Bean
    fun signupStatisticsQuery(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") transaction: TransactionTemplate,
        @Qualifier("readClock") clock: Clock,
    ): SignupStatisticsQuery {
        return JdbcSignupStatisticsQuery(jdbc, transaction, clock)
    }

    @Bean
    fun signupStatisticsController(statistics: SignupStatisticsQuery): SignupStatisticsController {
        return SignupStatisticsController(statistics)
    }
}
