package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations
import com.pawbridge.backend.animal.internal.recommendation.infrastructure.JdbcAnimalRecommendations
import com.pawbridge.backend.animal.internal.recommendation.infrastructure.PythonAnimalRecommendations
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationController
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationExceptionHandler
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.animal-recommendations", name = ["enabled"], havingValue = "true")
class AnimalRecommendationsConfiguration {
    @Bean
    fun pythonAnimalRecommendations(
        @Value("\${lost-search.python-url:\${python-ai-service.url}}") pythonUrl: String,
        @Value("\${python-ai-service.internal-api-key:}") internalKey: String,
        mapper: ObjectMapper,
    ): PythonAnimalRecommendations {
        return PythonAnimalRecommendations(pythonUrl, internalKey, mapper)
    }

    @Bean
    fun animalRecommendations(
        animals: AnimalDiscovery,
        python: PythonAnimalRecommendations,
        @Qualifier("dataSource") source: DataSource,
        @Qualifier("transactionManager") manager: PlatformTransactionManager,
    ): AnimalRecommendations {
        return JdbcAnimalRecommendations(animals, python, source, manager)
    }

    @Bean
    fun animalRecommendationController(recommendations: AnimalRecommendations): AnimalRecommendationController {
        return AnimalRecommendationController(recommendations)
    }

    @Bean
    fun animalRecommendationExceptionHandler(): AnimalRecommendationExceptionHandler {
        return AnimalRecommendationExceptionHandler()
    }
}
