package com.pawbridge.backend.animal.internal.discovery.infrastructure

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Order
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.PageRequest
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Search
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import javax.sql.DataSource

/** Adapted from PostgresqlAnimalQueryValidationTest at the pinned Animal source. */
class JdbcAnimalDiscoveryValidationTest {
    @Test
    fun unsupported_sort_and_unbounded_pages_are_rejected_before_query_sql() {
        val source = mock(DataSource::class.java)
        val manager = mock(PlatformTransactionManager::class.java)
        `when`(manager.getTransaction(any())).thenReturn(SimpleTransactionStatus())
        KoreanSearchAnalyzer().use { analyzer ->
            val service = JdbcAnimalDiscovery(source, manager, analyzer)
            val pages = arrayOf(
                PageRequest(0, 20, listOf(), false),
                page(0, 101),
                page(500, 20),
                page(0, 20, "unknown"),
                PageRequest(0, 20, listOf(Order("createdAt", true), Order("id", true)), true),
                page(0, 20, "relevance"),
            )
            for (page in pages) {
                assertThatThrownBy { service.search(Search.empty(), page) }
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
        }
        verifyNoInteractions(source)
    }

    @Test
    fun expensive_text_input_is_rejected_before_query_sql() {
        val source = mock(DataSource::class.java)
        val manager = mock(PlatformTransactionManager::class.java)
        `when`(manager.getTransaction(any())).thenReturn(SimpleTransactionStatus())
        KoreanSearchAnalyzer().use { analyzer ->
            val service = JdbcAnimalDiscovery(source, manager, analyzer)
            for (keyword in arrayOf("가".repeat(201), "a b c d e f g h i")) {
                val request = Search(null, null, null, null, null, null, null, null, null, keyword, null, null, null, null)
                assertThatThrownBy { service.search(request, page(0, 20)) }
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
        }
        verifyNoInteractions(source)
    }

    @Test
    fun transaction_begin_failure_precedes_invalid_page_or_sort_without_query_sql() {
        val source = mock(DataSource::class.java)
        val manager = mock(PlatformTransactionManager::class.java)
        val beginFailure = CannotCreateTransactionException("synthetic transaction begin failure")
        `when`(manager.getTransaction(any())).thenThrow(beginFailure)
        KoreanSearchAnalyzer().use { analyzer ->
            val service = JdbcAnimalDiscovery(source, manager, analyzer)
            for (invalid in arrayOf(page(0, 101), page(0, 20, "unknown"))) {
                assertThatThrownBy { service.search(Search.empty(), invalid) }.isSameAs(beginFailure)
            }
        }
        verifyNoInteractions(source)
    }

    private fun page(number: Int, size: Int, vararg properties: String): PageRequest {
        return PageRequest(number, size, properties.map { property -> Order(property, true) }, true)
    }
}
