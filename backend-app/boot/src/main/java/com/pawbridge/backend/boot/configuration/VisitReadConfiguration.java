package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.visit.application.ReadTodayVisits;
import com.pawbridge.backend.community.internal.visit.application.VisitCountReader;
import com.pawbridge.backend.community.internal.visit.infrastructure.JdbcVisitCountReader;
import com.pawbridge.backend.community.internal.visit.web.VisitTodayController;
import com.pawbridge.backend.community.internal.visit.web.VisitTodayExceptionHandler;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "pawbridge.visits.enabled", havingValue = "true")
public class VisitReadConfiguration {
    @Bean
    VisitCountReader visitCountReader(@Qualifier("readJdbc") JdbcTemplate jdbc,
                                     @Qualifier("readOnlyTransaction") TransactionTemplate transaction) {
        return new JdbcVisitCountReader(jdbc, transaction);
    }

    @Bean
    ReadTodayVisits readTodayVisits(VisitCountReader reader, @Qualifier("readClock") Clock clock) {
        return new ReadTodayVisits(reader, clock);
    }

    @Bean
    VisitTodayController visitTodayController(ReadTodayVisits visits) { return new VisitTodayController(visits); }

    @Bean
    VisitTodayExceptionHandler visitTodayExceptionHandler() { return new VisitTodayExceptionHandler(); }
}
