package com.pawbridge.backend.store.internal.directorder.infrastructure

import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

class JdbcProductSearchRebuildTest {
    private val jdbc = mockk<NamedParameterJdbcOperations>()
    private val terms = KoreanSearchTerms()
    private val events = ArrayList<String>()
    private val upserts = ArrayList<Map<String, *>>()
    private val failure: RuntimeException = IllegalStateException("synthetic recovery failure")
    private var failAt: String? = null
    private var markActual = true
    private lateinit var rebuild: JdbcProductSearchRebuild

    @BeforeEach
    fun prepare() {
        val transaction = object : TransactionOperations {
            override fun <T> execute(action: TransactionCallback<T>): T? {
                events.add("begin")
                val previous = TransactionSynchronizationManager.isActualTransactionActive()
                TransactionSynchronizationManager.setActualTransactionActive(markActual)
                try {
                    val result = action.doInTransaction(SimpleTransactionStatus())
                    events.add("commit")
                    if ("commit" == failAt) {
                        throw failure
                    }
                    return result
                } catch (rejected: RuntimeException) {
                    events.add("failure")
                    throw rejected
                } finally {
                    TransactionSynchronizationManager.setActualTransactionActive(previous)
                }
            }
        }
        rebuild = JdbcProductSearchRebuild(jdbc, transaction, terms)
    }

    @AfterEach
    fun closeAnalyzer() {
        terms.close()
    }

    @ParameterizedTest
    @CsvSource("1,1", "100,2")
    fun `누락되거나 오래된 문서가 한도 안에 있을 때__복구하면__각 원본을 읽고 기존 upsert를 한 트랜잭션에서 재사용한다`(
        limit: Int,
        count: Int,
    ) {
        // given
        every { jdbc.queryForList(any<String>(), any<Map<String, Any>>(), Long::class.javaObjectType) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("query")
            val sql = firstArg<String>()
            val values = secondArg<Map<String, *>>()
            assertEquals(KoreanSearchTerms.VERSION, values["version"])
            assertTrue(sql.contains("FROM pawbridge_store.products p JOIN pawbridge_store.product_search_source s ON s.product_id=p.id"))
            assertTrue(sql.contains("LEFT JOIN pawbridge_store.product_search_documents d ON d.product_id=p.id"))
            assertTrue(sql.contains("WHERE p.status='ACTIVE' AND (d.product_id IS NULL OR d.sku_id<>s.sku_id"))
            assertTrue(sql.contains("OR d.source_name<>s.name OR d.source_option<>s.option_name OR d.analyzer_version<>:version)"))
            assertTrue(sql.contains("ORDER BY p.id LIMIT :limit FOR UPDATE OF p SKIP LOCKED"))
            if ("query" == failAt) {
                throw failure
            }
            if ((values["limit"] as Int) == 1) listOf(2L) else listOf(2L, 8L)
        }
        every { jdbc.queryForMap(any<String>(), any<Map<String, Any>>()) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("source")
            assertEquals(
                "SELECT sku_id,name,option_name FROM pawbridge_store.product_search_source WHERE product_id=:id",
                firstArg<String>(),
            )
            val id = secondArg<Map<String, *>>()["id"] as Long
            mapOf<String, Any>("sku_id" to id * 10, "name" to "강아지 사료", "option_name" to "용량: 2kg")
        }
        every { jdbc.update(any<String>(), any<Map<String, Any>>()) } answers {
            events.add("upsert")
            assertTrue(firstArg<String>().contains("INSERT INTO pawbridge_store.product_search_documents"))
            upserts.add(secondArg<Map<String, *>>())
            if ("upsert" == failAt) {
                throw failure
            }
            1
        }

        // when
        val recovered = rebuild.rebuildPage(limit)

        // then
        assertEquals(count, recovered)
        assertEquals(count, upserts.size)
        for (index in 0 until count) {
            val values = upserts[index]
            val id = if (index == 0) 2L else 8L
            assertEquals(id, values["id"])
            assertEquals(id * 10, values["skuId"])
            assertEquals("강아지 사료", values["name"])
            assertEquals("용량: 2kg", values["option"])
            assertEquals(KoreanSearchTerms.VERSION, values["version"])
            assertFalse((values["nameTerms"] as String).isBlank())
            assertFalse((values["optionTerms"] as String).isBlank())
        }
        assertEquals("begin", events.first())
        assertEquals("query", events[1])
        assertEquals("commit", events.last())
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 101])
    fun `범위를 벗어난 페이지일 때__복구하면__트랜잭션에는 들어가지만 SQL은 실행하지 않는다`(limit: Int) {
        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) { rebuild.rebuildPage(limit) }

        // then
        assertNotNull(thrown)
        assertEquals(listOf("begin", "failure"), events)
        confirmVerified(jdbc)
    }

    @Test
    fun `실제 트랜잭션이 없는 트랜잭션 연산일 때__잘못된 한도로 복구하면__리소스 경계를 먼저 거부한다`() {
        // given
        markActual = false

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { rebuild.rebuildPage(0) }

        // then
        assertNotNull(thrown)
        confirmVerified(jdbc)
    }

    @Test
    fun `복구 후보가 없을 때__복구하면__0을 돌려주고 원본 조회나 upsert를 실행하지 않는다`() {
        // given
        every { jdbc.queryForList(any<String>(), any<Map<String, Any>>(), Long::class.javaObjectType) } returns listOf()

        // when
        val recovered = rebuild.rebuildPage(100)

        // then
        assertEquals(0, recovered)
        verify(exactly = 1) { jdbc.queryForList(any<String>(), any<Map<String, Any>>(), Long::class.javaObjectType) }
        verify(exactly = 0) { jdbc.queryForMap(any<String>(), any<Map<String, Any>>()) }
        verify(exactly = 0) { jdbc.update(any<String>(), any<Map<String, Any>>()) }
        confirmVerified(jdbc)
    }

    @ParameterizedTest
    @ValueSource(strings = ["query", "upsert", "commit"])
    fun `조회나 프로젝션이나 커밋이 실패할 때__복구하면__같은 실패를 전파하고 뒤따르는 작업을 멈춘다`(stage: String) {
        // given
        failAt = stage

        every { jdbc.queryForList(any<String>(), any<Map<String, Any>>(), Long::class.javaObjectType) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("query")
            val sql = firstArg<String>()
            val values = secondArg<Map<String, *>>()
            assertEquals(KoreanSearchTerms.VERSION, values["version"])
            assertTrue(sql.contains("FROM pawbridge_store.products p JOIN pawbridge_store.product_search_source s ON s.product_id=p.id"))
            assertTrue(sql.contains("LEFT JOIN pawbridge_store.product_search_documents d ON d.product_id=p.id"))
            assertTrue(sql.contains("WHERE p.status='ACTIVE' AND (d.product_id IS NULL OR d.sku_id<>s.sku_id"))
            assertTrue(sql.contains("OR d.source_name<>s.name OR d.source_option<>s.option_name OR d.analyzer_version<>:version)"))
            assertTrue(sql.contains("ORDER BY p.id LIMIT :limit FOR UPDATE OF p SKIP LOCKED"))
            if ("query" == failAt) {
                throw failure
            }
            if ((values["limit"] as Int) == 1) listOf(2L) else listOf(2L, 8L)
        }
        every { jdbc.queryForMap(any<String>(), any<Map<String, Any>>()) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("source")
            assertEquals(
                "SELECT sku_id,name,option_name FROM pawbridge_store.product_search_source WHERE product_id=:id",
                firstArg<String>(),
            )
            val id = secondArg<Map<String, *>>()["id"] as Long
            mapOf<String, Any>("sku_id" to id * 10, "name" to "강아지 사료", "option_name" to "용량: 2kg")
        }
        every { jdbc.update(any<String>(), any<Map<String, Any>>()) } answers {
            events.add("upsert")
            assertTrue(firstArg<String>().contains("INSERT INTO pawbridge_store.product_search_documents"))
            upserts.add(secondArg<Map<String, *>>())
            if ("upsert" == failAt) {
                throw failure
            }
            1
        }

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { rebuild.rebuildPage(100) }

        // then
        assertSame(failure, thrown)
        assertEquals(if (stage == "query") 0 else if (stage == "upsert") 1 else 2, upserts.size)
        assertEquals("failure", events.last())
        if (stage != "commit") {
            assertFalse(events.contains("commit"))
        }
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    }
}
