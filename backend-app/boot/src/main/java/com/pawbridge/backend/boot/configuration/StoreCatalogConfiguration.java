package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.store.internal.catalog.infrastructure.JdbcStoreCatalog;
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog;
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogController;
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogExceptionHandler;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Activation requires the Store reader privileges and routing ownership to be handed over. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.store-catalog", name = "enabled", havingValue = "true", matchIfMissing = false)
public class StoreCatalogConfiguration {
    @Bean
    JdbcTemplate storeCatalogJdbc(@Qualifier("dataSource") DataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        return jdbc;
    }

    @Bean
    TransactionTemplate storeCatalogReadTransaction(@Qualifier("transactionManager") JdbcTransactionManager manager) {
        TransactionTemplate reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        return reads;
    }

    @Bean(destroyMethod = "close")
    JdbcStoreCatalog storeCatalog(@Qualifier("storeCatalogJdbc") JdbcTemplate jdbc,
            @Qualifier("storeCatalogReadTransaction") TransactionTemplate reads) {
        return new JdbcStoreCatalog(new NamedParameterJdbcTemplate(jdbc), reads);
    }

    @Bean
    StoreCatalogController storeCatalogController(StoreCatalog catalog) { return new StoreCatalogController(catalog); }

    @Bean
    StoreCatalogExceptionHandler storeCatalogExceptionHandler() { return new StoreCatalogExceptionHandler(); }
}
