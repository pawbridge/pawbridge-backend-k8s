package com.pawbridge.backend.animal.internal.discovery.infrastructure

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Order
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.PageRequest
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Search
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import javax.sql.DataSource

/** Adapted from PostgresqlAnimalQueryValidationTest at the pinned Animal source. */
class JdbcAnimalDiscoveryValidationTest {
    @Test
    fun `지원하지 않는 정렬이나 무제한 페이지일 때__검색하면__질의 SQL 전에 거부한다`() {
        // given
        val source = mockk<DataSource>()
        val manager = mockk<PlatformTransactionManager>()

        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        justRun { manager.rollback(any()) }

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

            // when
            val failures = pages.map { page -> catchThrowable { service.search(Search.empty(), page) } }

            // then
            assertThat(failures).hasSize(pages.size)
            assertThat(failures).allSatisfy { failure ->
                assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            }
        }
        confirmVerified(source)
    }

    @Test
    fun `비싼 텍스트 입력일 때__검색하면__질의 SQL 전에 거부한다`() {
        // given
        val source = mockk<DataSource>()
        val manager = mockk<PlatformTransactionManager>()

        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        justRun { manager.rollback(any()) }

        KoreanSearchAnalyzer().use { analyzer ->
            val service = JdbcAnimalDiscovery(source, manager, analyzer)
            val keywords = arrayOf("가".repeat(201), "a b c d e f g h i")

            // when
            val failures = keywords.map { keyword ->
                val request = Search(null, null, null, null, null, null, null, null, null, keyword, null, null, null, null)
                catchThrowable { service.search(request, page(0, 20)) }
            }

            // then
            assertThat(failures).hasSize(keywords.size)
            assertThat(failures).allSatisfy { failure ->
                assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            }
        }
        confirmVerified(source)
    }

    @Test
    fun `트랜잭션 시작이 실패하고 페이지나 정렬이 잘못됐을 때__검색하면__질의 SQL 없이 시작 실패가 먼저 전파된다`() {
        // given
        val source = mockk<DataSource>()
        val manager = mockk<PlatformTransactionManager>()
        val beginFailure = CannotCreateTransactionException("synthetic transaction begin failure")

        every { manager.getTransaction(any()) } throws beginFailure

        KoreanSearchAnalyzer().use { analyzer ->
            val service = JdbcAnimalDiscovery(source, manager, analyzer)
            val invalidPages = arrayOf(page(0, 101), page(0, 20, "unknown"))

            // when
            val failures = invalidPages.map { invalid -> catchThrowable { service.search(Search.empty(), invalid) } }

            // then
            assertThat(failures).hasSize(invalidPages.size)
            assertThat(failures).allSatisfy { failure -> assertThat(failure).isSameAs(beginFailure) }
        }
        confirmVerified(source)
    }

    private fun page(number: Int, size: Int, vararg properties: String): PageRequest {
        return PageRequest(number, size, properties.map { property -> Order(property, true) }, true)
    }
}
