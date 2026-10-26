package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import java.io.IOException
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "'true'.equalsIgnoreCase('\${pawbridge.animal-discovery.enabled:false}') or " +
        "'true'.equalsIgnoreCase('\${pawbridge.animal-recommendations.enabled:false}') or " +
        "'true'.equalsIgnoreCase('\${pawbridge.animal-commands.enabled:false}') or " +
        "'true'.equalsIgnoreCase('\${pawbridge.shelter-commands.enabled:false}') or " +
        "'true'.equalsIgnoreCase('\${apms-photo-archive.enabled:false}') or " +
        "'true'.equalsIgnoreCase('\${apms.batch.schedule-enabled:false}') or " +
        "'true'.equalsIgnoreCase('\${pawbridge.apms-manual.enabled:false}') or " +
        "'true'.equalsIgnoreCase('\${shelter-directory.schedule-enabled:false}') or " +
        "('true'.equalsIgnoreCase('\${pawbridge.animal-chatbot.enabled:false}') and " +
        "'postgresql'.equals('\${pawbridge.animal-query.backend:}')) or " +
        "('true'.equalsIgnoreCase('\${pawbridge.animal-query.projection-schedule-enabled:false}') and " +
        "'postgresql'.equalsIgnoreCase('\${pawbridge.animal-query.backend:}') and " +
        "'true'.equalsIgnoreCase('\${pawbridge.animal-query.analyzed-text:false}'))",
)
class AnimalSearchConfiguration {
    @Bean(destroyMethod = "close")
    @Throws(IOException::class)
    fun animalDiscoveryAnalyzer(): KoreanSearchAnalyzer {
        return KoreanSearchAnalyzer()
    }
}
