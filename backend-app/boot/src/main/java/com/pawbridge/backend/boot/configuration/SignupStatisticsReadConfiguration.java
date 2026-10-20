package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery;
import com.pawbridge.backend.user.internal.statistics.infrastructure.JdbcSignupStatisticsQuery;
import com.pawbridge.backend.user.internal.statistics.web.SignupStatisticsController;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class SignupStatisticsReadConfiguration {
    @Bean
    SignupStatisticsQuery signupStatisticsQuery(@Qualifier("readJdbc") JdbcTemplate jdbc,
            @Qualifier("readOnlyTransaction") TransactionTemplate transaction, @Qualifier("readClock") Clock clock) {
        return new JdbcSignupStatisticsQuery(jdbc, transaction, clock);
    }

    @Bean
    SignupStatisticsController signupStatisticsController(SignupStatisticsQuery statistics) {
        return new SignupStatisticsController(statistics);
    }
}
