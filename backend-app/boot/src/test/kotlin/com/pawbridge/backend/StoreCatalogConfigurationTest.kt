package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.StoreCatalogConfiguration
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog
import com.pawbridge.backend.store.internal.catalog.infrastructure.JdbcStoreCatalog
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogController
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.function.Supplier
import javax.sql.DataSource

class StoreCatalogConfigurationTest {
    @Test
    fun given__no_explicit_enablement__when__starting_without_database_inputs__then__create_no_catalog_reader_or_routes() {
        for (value in arrayOf("", "pawbridge.store-catalog.enabled=false")) {
            var runner = ApplicationContextRunner().withUserConfiguration(StoreCatalogConfiguration::class.java)
            if (value.isNotEmpty()) {
                runner = runner.withPropertyValues(value)
            }
            runner.run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBeansOfType(StoreCatalog::class.java)).isEmpty()
                assertThat(context.getBeansOfType(StoreCatalogController::class.java)).isEmpty()
                assertThat(context.getBeansOfType(StoreCatalogExceptionHandler::class.java)).isEmpty()
                assertThat(context.getBeansOfType(JdbcTemplate::class.java)).isEmpty()
                assertThat(context.getBeansOfType(TransactionTemplate::class.java)).isEmpty()
            }
        }
    }

    @Test
    fun given__explicit_enablement__when__assembling_and_closing__then__reuse_reader_manager_with_five_second_queries_and_close_owned_nori() {
        val source = mock(DataSource::class.java)
        val manager = JdbcTransactionManager(source)
        val catalog = arrayOfNulls<JdbcStoreCatalog>(1)
        ApplicationContextRunner().withUserConfiguration(StoreCatalogConfiguration::class.java)
            .withBean("dataSource", DataSource::class.java, Supplier { source })
            .withBean("transactionManager", JdbcTransactionManager::class.java, Supplier { manager })
            .withPropertyValues("pawbridge.store-catalog.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                catalog[0] = context.getBean(JdbcStoreCatalog::class.java)
                val jdbc = context.getBean("storeCatalogJdbc", JdbcTemplate::class.java)
                val reads = context.getBean("storeCatalogReadTransaction", TransactionTemplate::class.java)
                assertThat(jdbc.dataSource).isSameAs(source)
                assertThat(jdbc.queryTimeout).isEqualTo(5)
                assertThat(reads.transactionManager).isSameAs(manager)
                assertThat(reads.isReadOnly).isTrue()
                assertThat(reads.timeout).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT)
                assertThat(reads.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
                val named = ReflectionTestUtils.getField(catalog[0]!!, "jdbc") as NamedParameterJdbcTemplate
                assertThat(named.jdbcOperations).isSameAs(jdbc)
                assertThat(ReflectionTestUtils.getField(catalog[0]!!, "transactions")).isSameAs(reads)
                assertThat(context.getBeansOfType(StoreCatalogController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(StoreCatalogExceptionHandler::class.java)).hasSize(1)
                // Observe close ownership without opening a database or repeating catalog SQL tests.
                val terms = ReflectionTestUtils.getField(catalog[0]!!, "terms")
                val analyzer = ReflectionTestUtils.getField(terms!!, "analyzer") as AutoCloseable
                val closeProbe = spy(analyzer)
                ReflectionTestUtils.setField(terms, "analyzer", closeProbe)
            }
        val terms = ReflectionTestUtils.getField(catalog[0]!!, "terms")
        verify(ReflectionTestUtils.getField(terms!!, "analyzer") as AutoCloseable).close()
        verify(source, never()).getConnection()
        verify(source, never()).getConnection(anyString(), anyString())
    }
}
