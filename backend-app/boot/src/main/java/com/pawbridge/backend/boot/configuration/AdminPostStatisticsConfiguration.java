package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcAdminPostStatisticsQuery;
import com.pawbridge.backend.community.internal.post.web.AdminPostStatisticsController;
import com.pawbridge.backend.community.internal.post.web.AdminPostStatisticsExceptionHandler;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class AdminPostStatisticsConfiguration {
    @Bean
    TransactionTemplate adminPostStatisticsTransaction(@Qualifier("transactionManager") JdbcTransactionManager manager) {
        var transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        transaction.setTimeout(3);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction;
    }

    @Bean
    AdminPostStatisticsQuery adminPostStatisticsQuery(@Qualifier("readJdbc") JdbcTemplate jdbc,
            @Qualifier("readOnlyTransaction") TransactionTemplate todayTransaction,
            @Qualifier("adminPostStatisticsTransaction") TransactionTemplate periodTransaction,
            @Qualifier("readClock") Clock clock) {
        return new JdbcAdminPostStatisticsQuery(jdbc, todayTransaction, periodTransaction, clock);
    }

    @Bean
    AdminPostStatisticsController adminPostStatisticsController(AdminPostStatisticsQuery statistics) {
        return new AdminPostStatisticsController(statistics);
    }

    @Bean
    AdminPostStatisticsExceptionHandler adminPostStatisticsExceptionHandler() { return new AdminPostStatisticsExceptionHandler(); }
}
