package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.StoreCatalogConfiguration
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogController
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogExceptionHandler
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.function.Supplier
import javax.sql.DataSource

class StoreCatalogConfigurationTest {
    @Test
    fun `명시적 활성화가 없을 때__DB 입력 없이 시작하면__카탈로그 리더와 경로를 만들지 않는다`() {
        // given
        val values = arrayOf("", "pawbridge.store-catalog.enabled=false")

        // when
        for (value in values) {
            var runner = ApplicationContextRunner().withUserConfiguration(StoreCatalogConfiguration::class.java)
            if (value.isNotEmpty()) {
                runner = runner.withPropertyValues(value)
            }
            runner.run { context ->
                // then
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
    fun `명시적 활성화가 있을 때__조립하고 닫으면__5초 질의 제한과 읽기 전용 트랜잭션을 쓴다`() {
        // given
        val source = mockk<DataSource>()
        val manager = JdbcTransactionManager(source)

        // when
        ApplicationContextRunner().withUserConfiguration(StoreCatalogConfiguration::class.java)
            .withBean("dataSource", DataSource::class.java, Supplier { source })
            .withBean("transactionManager", JdbcTransactionManager::class.java, Supplier { manager })
            .withPropertyValues("pawbridge.store-catalog.enabled=true")
            .run { context ->
                // then
                assertThat(context).hasNotFailed()
                val jdbc = context.getBean("storeCatalogJdbc", JdbcTemplate::class.java)
                val reads = context.getBean("storeCatalogReadTransaction", TransactionTemplate::class.java)
                assertThat(jdbc.queryTimeout).isEqualTo(5)
                assertThat(reads.isReadOnly).isTrue()
                assertThat(reads.timeout).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT)
                assertThat(reads.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
                assertThat(context.getBeansOfType(StoreCatalogController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(StoreCatalogExceptionHandler::class.java)).hasSize(1)
            }

        // then
        verify(exactly = 0) { source.connection }
        verify(exactly = 0) { source.getConnection(any(), any()) }
    }
}
