package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics
import com.pawbridge.backend.animal.internal.statistics.infrastructure.JdbcAnimalStatistics
import com.pawbridge.backend.animal.internal.statistics.web.AdminAnimalStatisticsController
import com.pawbridge.backend.animal.internal.statistics.web.AnimalStatisticsController
import com.pawbridge.backend.animal.internal.statistics.web.AnimalStatisticsExceptionHandler
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.animal-statistics", name = ["enabled"], havingValue = "true")
class AnimalStatisticsConfiguration {
    @Bean
    fun animalStatistics(
        @Qualifier("dataSource") source: DataSource,
        @Qualifier("transactionManager") manager: PlatformTransactionManager,
    ): AnimalStatistics {
        return JdbcAnimalStatistics(source, manager)
    }

    @Bean
    fun animalStatisticsController(statistics: AnimalStatistics): AnimalStatisticsController {
        return AnimalStatisticsController(statistics)
    }

    @Bean
    fun adminAnimalStatisticsController(statistics: AnimalStatistics): AdminAnimalStatisticsController {
        return AdminAnimalStatisticsController(statistics)
    }

    @Bean
    fun animalStatisticsExceptionHandler(): AnimalStatisticsExceptionHandler {
        return AnimalStatisticsExceptionHandler()
    }
}
