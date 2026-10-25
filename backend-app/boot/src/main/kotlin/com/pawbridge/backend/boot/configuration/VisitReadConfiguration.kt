package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.visit.application.ReadTodayVisits
import com.pawbridge.backend.community.internal.visit.application.VisitCountReader
import com.pawbridge.backend.community.internal.visit.infrastructure.JdbcVisitCountReader
import com.pawbridge.backend.community.internal.visit.web.VisitTodayController
import com.pawbridge.backend.community.internal.visit.web.VisitTodayExceptionHandler
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["pawbridge.visits.enabled"], havingValue = "true")
class VisitReadConfiguration {
    @Bean
    fun visitCountReader(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") transaction: TransactionTemplate,
    ): VisitCountReader {
        return JdbcVisitCountReader(jdbc, transaction)
    }

    @Bean
    fun readTodayVisits(reader: VisitCountReader, @Qualifier("readClock") clock: Clock): ReadTodayVisits {
        return ReadTodayVisits(reader, clock)
    }

    @Bean
    fun visitTodayController(visits: ReadTodayVisits): VisitTodayController {
        return VisitTodayController(visits)
    }

    @Bean
    fun visitTodayExceptionHandler(): VisitTodayExceptionHandler {
        return VisitTodayExceptionHandler()
    }
}
