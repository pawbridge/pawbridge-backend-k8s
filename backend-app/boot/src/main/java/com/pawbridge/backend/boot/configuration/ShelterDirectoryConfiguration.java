package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter;
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure.ShelterDirectoryClient;
import com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure.ShelterDirectoryCollector;
import com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure.ShelterDirectoryStore;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

/** Original shelter directory job and schedule, assembled only by the original schedule switch. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "shelter-directory", name = "schedule-enabled", havingValue = "true")
@Import({AnimalSearchConfiguration.class, ShelterDirectorySchedule.class})
public class ShelterDirectoryConfiguration {
    @Bean
    ShelterDirectoryClient shelterDirectoryClient(@Value("${shelter-directory.service-key:}") String key, ObjectMapper mapper) {
        return new ShelterDirectoryClient(key, mapper);
    }

    @Bean
    ShelterDirectoryStore shelterDirectoryStore(ObjectMapper mapper,
            @Qualifier("writeDataSource") HikariDataSource source, KoreanSearchAnalyzer analyzer) {
        // Not a bean: existing Animal writes inject the single SearchDocumentWriter by type.
        return new ShelterDirectoryStore(mapper, new SearchDocumentWriter(source, analyzer));
    }

    @Bean
    ShelterDirectoryCollector shelterDirectoryCollector(@Qualifier("writeDataSource") HikariDataSource source,
            ShelterDirectoryClient client, ShelterDirectoryStore store) {
        return new ShelterDirectoryCollector(source, client, store);
    }

    @Bean
    JobRepository shelterDirectoryJobRepository(@Qualifier("writeDataSource") HikariDataSource source,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager) throws Exception {
        if (manager.getDataSource() != source)
            throw new IllegalArgumentException("Shelter directory must use the same existing writer DataSource");
        // Explicit type and schema avoid JDBC metadata access, search_path assumptions and schema creation.
        var factory = new JobRepositoryFactoryBean();
        factory.setDataSource(source); factory.setTransactionManager(manager);
        factory.setDatabaseType("POSTGRES"); factory.setTablePrefix("pawbridge_animal.BATCH_");
        factory.setIsolationLevelForCreate("ISOLATION_SERIALIZABLE");
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    @Bean
    Job shelterDirectoryJob(@Qualifier("shelterDirectoryJobRepository") JobRepository repository,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager, ShelterDirectoryCollector collector) {
        var noTransaction = new DefaultTransactionAttribute();
        noTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var step = new StepBuilder("shelterDirectoryStep", repository)
                .tasklet((contribution, context) -> {
                    var result = collector.collect();
                    contribution.incrementWriteCount(result);
                    return RepeatStatus.FINISHED;
                }, manager).transactionAttribute(noTransaction).build();
        return new JobBuilder("shelterDirectoryJob", repository).start(step).build();
    }
}
