package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics;
import com.pawbridge.backend.animal.internal.statistics.infrastructure.JdbcAnimalStatistics;
import com.pawbridge.backend.animal.internal.statistics.web.AdminAnimalStatisticsController;
import com.pawbridge.backend.animal.internal.statistics.web.AnimalStatisticsController;
import com.pawbridge.backend.animal.internal.statistics.web.AnimalStatisticsExceptionHandler;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.animal-statistics", name = "enabled", havingValue = "true")
public class AnimalStatisticsConfiguration {
    @Bean
    AnimalStatistics animalStatistics(@Qualifier("dataSource") DataSource source,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new JdbcAnimalStatistics(source, manager);
    }

    @Bean
    AnimalStatisticsController animalStatisticsController(AnimalStatistics statistics) { return new AnimalStatisticsController(statistics); }

    @Bean
    AdminAnimalStatisticsController adminAnimalStatisticsController(AnimalStatistics statistics) { return new AdminAnimalStatisticsController(statistics); }

    @Bean
    AnimalStatisticsExceptionHandler animalStatisticsExceptionHandler() { return new AnimalStatisticsExceptionHandler(); }
}
