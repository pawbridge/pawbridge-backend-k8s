package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.infrastructure.JdbcAnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryController
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryExceptionHandler
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "'true'.equalsIgnoreCase('\${pawbridge.animal-discovery.enabled:false}') or " +
        "'true'.equalsIgnoreCase('\${pawbridge.animal-recommendations.enabled:false}')",
)
@Import(AnimalSearchConfiguration::class)
class AnimalDiscoveryConfiguration {
    @Bean
    fun animalDiscovery(
        @Qualifier("dataSource") source: DataSource,
        @Qualifier("transactionManager") manager: PlatformTransactionManager,
        analyzer: KoreanSearchAnalyzer,
    ): AnimalDiscovery {
        return JdbcAnimalDiscovery(source, manager, analyzer)
    }

    @Bean
    @ConditionalOnProperty(prefix = "pawbridge.animal-discovery", name = ["enabled"], havingValue = "true")
    fun animalDiscoveryController(animals: AnimalDiscovery): AnimalDiscoveryController {
        return AnimalDiscoveryController(animals)
    }

    @Bean
    @ConditionalOnProperty(prefix = "pawbridge.animal-discovery", name = ["enabled"], havingValue = "true")
    fun animalDiscoveryExceptionHandler(): AnimalDiscoveryExceptionHandler {
        return AnimalDiscoveryExceptionHandler()
    }
}
