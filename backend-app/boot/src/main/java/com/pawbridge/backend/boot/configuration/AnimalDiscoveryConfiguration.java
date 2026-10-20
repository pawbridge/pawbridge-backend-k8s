package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery;
import com.pawbridge.backend.animal.internal.discovery.infrastructure.JdbcAnimalDiscovery;
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryController;
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryExceptionHandler;
import java.io.IOException;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${pawbridge.animal-discovery.enabled:false} or ${pawbridge.animal-recommendations.enabled:false}")
public class AnimalDiscoveryConfiguration {
    @Bean(destroyMethod = "close")
    KoreanSearchAnalyzer animalDiscoveryAnalyzer() throws IOException { return new KoreanSearchAnalyzer(); }

    @Bean
    AnimalDiscovery animalDiscovery(@Qualifier("dataSource") DataSource source,
            @Qualifier("transactionManager") PlatformTransactionManager manager, KoreanSearchAnalyzer analyzer) {
        return new JdbcAnimalDiscovery(source, manager, analyzer);
    }

    @Bean
    @ConditionalOnProperty(prefix = "pawbridge.animal-discovery", name = "enabled", havingValue = "true")
    AnimalDiscoveryController animalDiscoveryController(AnimalDiscovery animals) { return new AnimalDiscoveryController(animals); }

    @Bean
    @ConditionalOnProperty(prefix = "pawbridge.animal-discovery", name = "enabled", havingValue = "true")
    AnimalDiscoveryExceptionHandler animalDiscoveryExceptionHandler() { return new AnimalDiscoveryExceptionHandler(); }
}
