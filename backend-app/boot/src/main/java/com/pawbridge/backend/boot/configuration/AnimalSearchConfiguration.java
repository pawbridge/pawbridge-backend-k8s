package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'true'.equalsIgnoreCase('${pawbridge.animal-discovery.enabled:false}') or "
        + "'true'.equalsIgnoreCase('${pawbridge.animal-recommendations.enabled:false}') or "
        + "'true'.equalsIgnoreCase('${pawbridge.animal-commands.enabled:false}') or "
        + "'true'.equalsIgnoreCase('${pawbridge.shelter-commands.enabled:false}') or "
        + "'true'.equalsIgnoreCase('${apms.batch.schedule-enabled:false}')")
public class AnimalSearchConfiguration {
    @Bean(destroyMethod = "close")
    KoreanSearchAnalyzer animalDiscoveryAnalyzer() throws IOException { return new KoreanSearchAnalyzer(); }
}
