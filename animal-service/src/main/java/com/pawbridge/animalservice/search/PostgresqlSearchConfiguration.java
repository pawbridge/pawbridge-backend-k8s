package com.pawbridge.animalservice.search;

import java.io.IOException;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.animal-query",name="backend",havingValue="postgresql")
public class PostgresqlSearchConfiguration {
    @Bean(destroyMethod="close")
    @ConditionalOnProperty(prefix="pawbridge.animal-query",name="analyzed-text",havingValue="true")
    KoreanSearchAnalyzer koreanSearchAnalyzer() throws IOException { return new KoreanSearchAnalyzer(); }

    @Bean
    @ConditionalOnProperty(prefix="pawbridge.animal-query",name="analyzed-text",havingValue="true")
    PostgresqlSearchProjector postgresqlSearchProjector(DataSource source,KoreanSearchAnalyzer analyzer) {
        return new PostgresqlSearchProjector(source,analyzer);
    }
}
