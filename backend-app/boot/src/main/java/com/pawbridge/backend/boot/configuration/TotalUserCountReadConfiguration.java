package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery;
import com.pawbridge.backend.user.internal.statistics.infrastructure.JdbcTotalUserCountQuery;
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController;
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class TotalUserCountReadConfiguration {
    @Bean
    TotalUserCountQuery totalUserCountQuery(@Qualifier("readJdbc") JdbcTemplate jdbc,
                                          @Qualifier("readOnlyTransaction") TransactionTemplate transaction) {
        return new JdbcTotalUserCountQuery(jdbc, transaction);
    }

    @Bean
    TotalUserCountController totalUserCountController(TotalUserCountQuery users) {
        return new TotalUserCountController(users);
    }

    @Bean
    TotalUserCountExceptionHandler totalUserCountExceptionHandler() {
        return new TotalUserCountExceptionHandler();
    }
}
