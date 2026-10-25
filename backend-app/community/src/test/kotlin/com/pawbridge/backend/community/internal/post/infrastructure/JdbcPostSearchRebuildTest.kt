package com.pawbridge.backend.community.internal.post.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.infrastructure.search.KoreanSearchTerms
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

class JdbcPostSearchRebuildTest {
    private val jdbc = mockk<JdbcOperations>()
    private val events = ArrayList<String>()
    private val calls = ArrayList<StatementCall>()
    private val connection = recordingConnection(calls) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
    }
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
    }

    @AfterEach
    fun closeAnalyzer() {
        writer.close()
    }

    @ParameterizedTest
    @CsvSource("1,1", "100,2")
    fun `범위가 제한된 누락 또는 오래된 문서일 때__복구하면__한 트랜잭션에서 기존 가중 업서트와 분석기를 쓴다`(
        limit: Int,
        count: Int,
    ) {
        // given
        every { jdbc.queryForList(any<String>(), *anyVararg()) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("query")
            val bound = args[1] as Array<*>
            assertEquals(KoreanSearchTerms.VERSION, bound[0])
            val limit = bound[1] as Int
            val sql = firstArg<String>()
            assertTrue(sql.contains("p.deleted_at IS NULL"))
            assertTrue(sql.contains("d.post_id IS NULL OR d.title_hash<>md5(p.title)"))
            assertTrue(sql.contains("d.content_hash<>md5(p.content) OR d.analyzer_version<>?"))
            assertTrue(sql.contains("ORDER BY p.post_id LIMIT ? FOR UPDATE OF p SKIP LOCKED"))
            if ("query" == failAt) {
                throw failure
            }
            if (limit == 1) listOf(row(2)) else listOf(row(2), row(8))
        }

        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            events.add("upsert")
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            if ("upsert" == failAt) {
                throw failure
            }
            1
        }

        // when
        val rebuilt = rebuild.rebuildPage(limit)

        // then
        assertEquals(count, rebuilt)
        assertEquals(count, calls.size)
        assertTrue(calls.all { it.sql.contains("ON CONFLICT (post_id) DO UPDATE") })
        for (index in 0 until count) {
            val values = calls[index].values
            assertEquals(if (index == 0) 2L else 8L, values[1])
            assertEquals("유기 동물", values[2])
            assertEquals("보호소 소식", values[3])
            assertEquals(KoreanSearchTerms.VERSION, values[4])
            assertFalse((values[5] as String).isBlank())
            assertFalse((values[6] as String).isBlank())
        }
        assertEquals("begin", events.first())
        assertEquals("query", events[1])
        assertEquals("commit", events.last())
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 101])
    fun `범위를 벗어난 페이지일 때__복구하면__트랜잭션에는 들어가지만 SQL은 실행하지 않는다`(limit: Int) {
        // given
        val outOfRange = limit

        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) { rebuild.rebuildPage(outOfRange) }

        // then
        assertEquals(IllegalArgumentException::class.java, thrown.javaClass)
        assertEquals(listOf("begin", "failure"), events)
        confirmVerified(jdbc)
    }

    @Test
    fun `실제 트랜잭션이 없는 트랜잭션 연산일 때__잘못된 한계로 복구하면__자원 경계 검사를 먼저 거부한다`() {
        // given
        markActual = false

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { rebuild.rebuildPage(0) }

        // then
        assertEquals(IllegalStateException::class.java, thrown.javaClass)
        confirmVerified(jdbc)
    }

    @Test
    fun `복구 후보가 없을 때__복구하면__0을 돌려주고 업서트를 내지 않는다`() {
        // given
        every { jdbc.queryForList(any<String>(), *anyVararg()) } returns listOf()

        // when
        val rebuilt = rebuild.rebuildPage(100)

        // then
        assertEquals(0, rebuilt)
        verify(exactly = 0) { jdbc.update(any<PreparedStatementCreator>()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["query", "upsert", "commit"])
    fun `조회나 투영이나 커밋이 실패할 때__복구하면__같은 예외를 전파하고 이후 작업을 멈춘다`(
        stage: String,
    ) {
        // given
        failAt = stage

        every { jdbc.queryForList(any<String>(), *anyVararg()) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("query")
            val bound = args[1] as Array<*>
            assertEquals(KoreanSearchTerms.VERSION, bound[0])
            val limit = bound[1] as Int
            val sql = firstArg<String>()
            assertTrue(sql.contains("p.deleted_at IS NULL"))
            assertTrue(sql.contains("d.post_id IS NULL OR d.title_hash<>md5(p.title)"))
            assertTrue(sql.contains("d.content_hash<>md5(p.content) OR d.analyzer_version<>?"))
            assertTrue(sql.contains("ORDER BY p.post_id LIMIT ? FOR UPDATE OF p SKIP LOCKED"))
            if ("query" == failAt) {
                throw failure
            }
            if (limit == 1) listOf(row(2)) else listOf(row(2), row(8))
        }

        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            events.add("upsert")
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            if ("upsert" == failAt) {
                throw failure
            }
            1
        }

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { rebuild.rebuildPage(100) }

        // then
        assertSame(failure, thrown)
        assertEquals(if (stage == "query") 0 else if (stage == "upsert") 1 else 2, calls.size)
        assertEquals("failure", events.last())
        if (stage != "commit") {
            assertFalse(events.contains("commit"))
        }
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    }

    private fun row(id: Long): Map<String, Any> = mapOf("post_id" to id, "title" to "유기 동물", "content" to "보호소 소식")
}
