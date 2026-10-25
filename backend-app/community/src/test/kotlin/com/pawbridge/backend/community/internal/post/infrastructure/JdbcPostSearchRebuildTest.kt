package com.pawbridge.backend.community.internal.post.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.infrastructure.search.KoreanSearchTerms
import com.pawbridge.backend.community.support.anyVarargs
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
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when` as whenever
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.PreparedStatement

class JdbcPostSearchRebuildTest {
    private val jdbc = mock(JdbcOperations::class.java)
    private val connection = mock(Connection::class.java)
    private val events = ArrayList<String>()
    private val bindings = ArrayList<MutableList<Any?>>()
    private val failure: RuntimeException = IllegalStateException("synthetic recovery failure")
    private var failAt: String? = null
    private var markActual = true
    private lateinit var writer: JdbcPostWriter
    private lateinit var rebuild: JdbcPostSearchRebuild

    @BeforeEach
    fun prepare() {
        val transaction = object : TransactionOperations {
            override fun <T : Any?> execute(callback: TransactionCallback<T>): T? {
                events.add("begin")
                val previous = TransactionSynchronizationManager.isActualTransactionActive()
                TransactionSynchronizationManager.setActualTransactionActive(markActual)
                try {
                    val result = callback.doInTransaction(SimpleTransactionStatus())
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
        writer = JdbcPostWriter(jdbc, transaction, ObjectMapper())
        rebuild = JdbcPostSearchRebuild(jdbc, transaction, writer)
        whenever(jdbc.queryForList(anyString(), *anyVarargs())).thenAnswer { call ->
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("query")
            assertEquals(KoreanSearchTerms.VERSION, call.getArgument<Any>(1))
            val limit: Int = call.getArgument(2)
            val sql: String = call.getArgument(0)
            assertTrue(sql.contains("p.deleted_at IS NULL"))
            assertTrue(sql.contains("d.post_id IS NULL OR d.title_hash<>md5(p.title)"))
            assertTrue(sql.contains("d.content_hash<>md5(p.content) OR d.analyzer_version<>?"))
            assertTrue(sql.contains("ORDER BY p.post_id LIMIT ? FOR UPDATE OF p SKIP LOCKED"))
            if ("query" == failAt) {
                throw failure
            }
            if (limit == 1) listOf(row(2)) else listOf(row(2), row(8))
        }
        whenever(connection.prepareStatement(anyString())).thenAnswer { call ->
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            assertTrue(call.getArgument<String>(0).contains("ON CONFLICT (post_id) DO UPDATE"))
            val values: MutableList<Any?> = MutableList(6) { null }
            bindings.add(values)
            val statement = mock(PreparedStatement::class.java)
            doAnswer { binding ->
                values[binding.getArgument<Int>(0) - 1] = binding.getArgument(1)
                null
            }.`when`(statement).setObject(anyInt(), any())
            statement
        }
        whenever(jdbc.update(any(PreparedStatementCreator::class.java))).thenAnswer { call ->
            events.add("upsert")
            call.getArgument<PreparedStatementCreator>(0).createPreparedStatement(connection)
            if ("upsert" == failAt) {
                throw failure
            }
            1
        }
    }

    @AfterEach
    fun closeAnalyzer() {
        writer.close()
    }

    @ParameterizedTest
    @CsvSource("1,1", "100,2")
    fun given__bounded_missing_or_stale_documents__when__recovering__then__use_one_transaction_and_the_existing_weighted_upsert_and_analyzer(
        limit: Int,
        count: Int,
    ) {
        assertEquals(count, rebuild.rebuildPage(limit))
        assertEquals(count, bindings.size)
        for (index in 0 until count) {
            val values = bindings[index]
            assertEquals(if (index == 0) 2L else 8L, values[0])
            assertEquals("유기 동물", values[1])
            assertEquals("보호소 소식", values[2])
            assertEquals(KoreanSearchTerms.VERSION, values[3])
            assertFalse((values[4] as String).isBlank())
            assertFalse((values[5] as String).isBlank())
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
    fun given__no_recovery_candidates__when__recovering__then__return_zero_and_issue_no_upsert() {
        // doReturn avoids invoking the answer stubbed in prepare() while this stubbing is recorded.
        doReturn(listOf<Map<String, Any>>()).`when`(jdbc).queryForList(anyString(), *anyVarargs())

        assertEquals(0, rebuild.rebuildPage(100))
        verify(jdbc, never()).update(any(PreparedStatementCreator::class.java))
    }

    @ParameterizedTest
    @ValueSource(strings = ["query", "upsert", "commit"])
    fun given__query_projection_or_commit_failure__when__recovering__then__propagate_the_same_failure_and_stop_following_work(
        stage: String,
    ) {
        failAt = stage

        assertSame(failure, assertThrows(IllegalStateException::class.java) { rebuild.rebuildPage(100) })
        assertEquals(if (stage == "query") 0 else if (stage == "upsert") 1 else 2, bindings.size)
        assertEquals("failure", events.last())
        if (stage != "commit") {
            assertFalse(events.contains("commit"))
        }
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    }

    private fun row(id: Long): Map<String, Any> = mapOf("post_id" to id, "title" to "유기 동물", "content" to "보호소 소식")
}
