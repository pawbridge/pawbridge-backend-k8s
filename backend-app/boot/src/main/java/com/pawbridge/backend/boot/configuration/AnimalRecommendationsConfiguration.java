package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery;
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations;
import com.pawbridge.backend.animal.internal.recommendation.infrastructure.JdbcAnimalRecommendations;
import com.pawbridge.backend.animal.internal.recommendation.infrastructure.PythonAnimalRecommendations;
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationController;
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationExceptionHandler;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.animal-recommendations", name = "enabled", havingValue = "true")
public class AnimalRecommendationsConfiguration {
    @Bean
    PythonAnimalRecommendations pythonAnimalRecommendations(
            @Value("${lost-search.python-url:${python-ai-service.url}}") String pythonUrl,
            @Value("${python-ai-service.internal-api-key:}") String internalKey, ObjectMapper mapper) {
        return new PythonAnimalRecommendations(pythonUrl, internalKey, mapper);
    }

    @Bean
    AnimalRecommendations animalRecommendations(AnimalDiscovery animals, PythonAnimalRecommendations python,
            @Qualifier("dataSource") DataSource source,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new JdbcAnimalRecommendations(animals, python, source, manager);
    }

    @Bean
    AnimalRecommendationController animalRecommendationController(AnimalRecommendations recommendations) {
        return new AnimalRecommendationController(recommendations);
    }

    @Bean
    AnimalRecommendationExceptionHandler animalRecommendationExceptionHandler() { return new AnimalRecommendationExceptionHandler(); }
}
