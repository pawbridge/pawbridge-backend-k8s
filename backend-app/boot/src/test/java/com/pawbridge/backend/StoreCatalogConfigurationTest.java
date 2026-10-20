package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.boot.configuration.StoreCatalogConfiguration;
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog;
import com.pawbridge.backend.store.internal.catalog.infrastructure.JdbcStoreCatalog;
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogController;
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogExceptionHandler;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class StoreCatalogConfigurationTest {
    @Test
    void given__no_explicit_enablement__when__starting_without_database_inputs__then__create_no_catalog_reader_or_routes() {
        for (String value : new String[]{"", "pawbridge.store-catalog.enabled=false"}) {
            var runner = new ApplicationContextRunner().withUserConfiguration(StoreCatalogConfiguration.class);
            if (!value.isEmpty()) runner = runner.withPropertyValues(value);
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansOfType(StoreCatalog.class)).isEmpty();
                assertThat(context.getBeansOfType(StoreCatalogController.class)).isEmpty();
                assertThat(context.getBeansOfType(StoreCatalogExceptionHandler.class)).isEmpty();
                assertThat(context.getBeansOfType(JdbcTemplate.class)).isEmpty();
                assertThat(context.getBeansOfType(TransactionTemplate.class)).isEmpty();
            });
        }
    }

    @Test
    void given__explicit_enablement__when__assembling_and_closing__then__reuse_reader_manager_with_five_second_queries_and_close_owned_nori() throws Exception {
        DataSource source = mock(DataSource.class);
        JdbcTransactionManager manager = new JdbcTransactionManager(source);
        JdbcStoreCatalog[] catalog = new JdbcStoreCatalog[1];
        new ApplicationContextRunner().withUserConfiguration(StoreCatalogConfiguration.class)
                .withBean("dataSource", DataSource.class, () -> source)
                .withBean("transactionManager", JdbcTransactionManager.class, () -> manager)
                .withPropertyValues("pawbridge.store-catalog.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    catalog[0] = context.getBean(JdbcStoreCatalog.class);
                    JdbcTemplate jdbc = context.getBean("storeCatalogJdbc", JdbcTemplate.class);
                    TransactionTemplate reads = context.getBean("storeCatalogReadTransaction", TransactionTemplate.class);
                    assertThat(jdbc.getDataSource()).isSameAs(source);
                    assertThat(jdbc.getQueryTimeout()).isEqualTo(5);
                    assertThat(reads.getTransactionManager()).isSameAs(manager);
                    assertThat(reads.isReadOnly()).isTrue();
                    assertThat(reads.getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
                    assertThat(reads.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
                    var named = (NamedParameterJdbcTemplate) ReflectionTestUtils.getField(catalog[0], "jdbc");
                    assertThat(named.getJdbcOperations()).isSameAs(jdbc);
                    assertThat(ReflectionTestUtils.getField(catalog[0], "transactions")).isSameAs(reads);
                    assertThat(context.getBeansOfType(StoreCatalogController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(StoreCatalogExceptionHandler.class)).hasSize(1);
                    // Observe close ownership without opening a database or repeating catalog SQL tests.
                    Object terms = ReflectionTestUtils.getField(catalog[0], "terms");
                    AutoCloseable analyzer = (AutoCloseable) ReflectionTestUtils.getField(terms, "analyzer");
                    AutoCloseable closeProbe = spy(analyzer);
                    ReflectionTestUtils.setField(terms, "analyzer", closeProbe);
                });
        Object terms = ReflectionTestUtils.getField(catalog[0], "terms");
        verify((AutoCloseable) ReflectionTestUtils.getField(terms, "analyzer")).close();
        verify(source, never()).getConnection();
        verify(source, never()).getConnection(anyString(), anyString());
    }
}
