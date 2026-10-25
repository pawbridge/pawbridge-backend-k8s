package com.pawbridge.backend.store.internal.directorder.infrastructure

import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

class JdbcProductSearchRebuildTest {
    private val jdbc: NamedParameterJdbcOperations = mock(NamedParameterJdbcOperations::class.java)
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
        `when`(jdbc.queryForList(anyString(), anyMap<String, Any>(), eq(Long::class.javaObjectType))).thenAnswer { call ->
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("query")
            val sql = call.getArgument<String>(0)
            val values = call.getArgument<Map<String, *>>(1)
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
        `when`(jdbc.queryForMap(anyString(), anyMap<String, Any>())).thenAnswer { call ->
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("source")
            assertEquals(
                "SELECT sku_id,name,option_name FROM pawbridge_store.product_search_source WHERE product_id=:id",
                call.getArgument<String>(0),
            )
            val id = call.getArgument<Map<String, *>>(1)["id"] as Long
            mapOf<String, Any>("sku_id" to id * 10, "name" to "강아지 사료", "option_name" to "용량: 2kg")
        }
        `when`(jdbc.update(anyString(), anyMap<String, Any>())).thenAnswer { call ->
            events.add("upsert")
            assertTrue(call.getArgument<String>(0).contains("INSERT INTO pawbridge_store.product_search_documents"))
            upserts.add(call.getArgument<Map<String, *>>(1))
            if ("upsert" == failAt) {
                throw failure
            }
            1
        }
    }

    @AfterEach
    fun closeAnalyzer() {
        terms.close()
    }

    @ParameterizedTest
    @CsvSource("1,1", "100,2")
    fun given__bounded_missing_or_stale_documents__when__recovering__then__read_each_source_and_reuse_the_existing_upsert_in_one_transaction(
        limit: Int,
        count: Int,
    ) {
        assertEquals(count, rebuild.rebuildPage(limit))
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
    fun given__an_out_of_range_page__when__recovering__then__enter_the_transaction_but_execute_no_sql(limit: Int) {
        assertThrows(IllegalArgumentException::class.java) { rebuild.rebuildPage(limit) }

        assertEquals(listOf("begin", "failure"), events)
        verifyNoInteractions(jdbc)
    }

    @Test
    fun given__a_transaction_operation_without_an_actual_transaction__when__recovering_an_invalid_limit__then__reject_the_resource_boundary_first() {
        markActual = false

        assertThrows(IllegalStateException::class.java) { rebuild.rebuildPage(0) }

        verifyNoInteractions(jdbc)
    }

    @Test
    fun given__no_recovery_candidates__when__recovering__then__return_zero_and_issue_no_source_read_or_upsert() {
        `when`(jdbc.queryForList(anyString(), anyMap<String, Any>(), eq(Long::class.javaObjectType))).thenReturn(listOf())

        assertEquals(0, rebuild.rebuildPage(100))

        verify(jdbc, never()).queryForMap(anyString(), anyMap<String, Any>())
        verify(jdbc, never()).update(anyString(), anyMap<String, Any>())
    }

    @ParameterizedTest
    @ValueSource(strings = ["query", "upsert", "commit"])
    fun given__query_projection_or_commit_failure__when__recovering__then__propagate_the_same_failure_and_stop_following_work(
        stage: String,
    ) {
        failAt = stage

        assertSame(failure, assertThrows(IllegalStateException::class.java) { rebuild.rebuildPage(100) })

        assertEquals(if (stage == "query") 0 else if (stage == "upsert") 1 else 2, upserts.size)
        assertEquals("failure", events.last())
        if (stage != "commit") {
            assertFalse(events.contains("commit"))
        }
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    }
}
