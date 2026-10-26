package com.pawbridge.backend.animal.internal.command.infrastructure.search

import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.function.IntUnaryOperator

class PostgresqlSearchProjectorTest {
    private val jdbc = mockk<JdbcTemplate>()
    private val transaction = mockk<TransactionTemplate>()
    private val analyzer = mockk<KoreanSearchAnalyzer>()
    private val projector = PostgresqlSearchProjector(jdbc, transaction, analyzer)
    private var callbackActive = false
    private var callbacks = 0

    @AfterEach
    fun clearOnlyTheTransactionMarkerSetByThisTest() {
        TransactionSynchronizationManager.setActualTransactionActive(false)
    }

    @Test
    fun `한도 값이 잘못됐을 때__어떤 갱신이든 요청하면__트랜잭션이나 SQL 전에 거부한다`() {
        // given
        val limits = intArrayOf(-1, 0, 101)

        // when
        val failures = refreshMethods().flatMap { refresh ->
            limits.map { limit -> assertThatThrownBy { refresh.applyAsInt(limit) } }
        }

        // then
        failures.forEach { failure -> failure.isInstanceOf(IllegalArgumentException::class.java) }
        confirmVerified(transaction, jdbc, analyzer)
    }

    @Test
    fun `호출자의 실제 트랜잭션이 있을 때__어떤 갱신이든 요청하면__중첩 트랜잭션이나 SQL 없이 거부한다`() {
        // given
        TransactionSynchronizationManager.setActualTransactionActive(true)

        // when
        val failures = refreshMethods().map { refresh -> assertThatThrownBy { refresh.applyAsInt(50) } }

        // then
        failures.forEach { failure -> failure.isInstanceOf(IllegalStateException::class.java) }
        confirmVerified(transaction, jdbc, analyzer)
    }

    @Test
    fun `변경 표시된 동물이 있을 때__갱신하면__한 쪽 분량만 잠그고 콜백 안에서 기존 작성기를 재사용한다`() {
        // given
        every { transaction.execute(any<TransactionCallback<Any>>()) } answers {
            runCallback(firstArg<TransactionCallback<*>>())
        }
        every { jdbc.execute(any<String>()) } answers { assertCallbackActive() }
        every { jdbc.queryForList(any<String>(), *anyVararg<Any>()) } answers {
            assertCallbackActive()
            listOf(animalRow())
        }
        every { analyzer.animalTerms(any(), any(), any(), any(), any()) } returns
            KoreanSearchAnalyzer.AnimalTerms("귀", "믹스견", "갈색", "순함", "서울", "관계")
        every { jdbc.update(any<String>(), *anyVararg<Any>()) } returns 0

        // when
        val refreshed = projector.refreshDirtyAnimals(2)

        // then
        val sql = slot<String>()
        assertThat(refreshed).isEqualTo(1)
        verifyOrder {
            jdbc.execute("SET LOCAL lock_timeout='1s'")
            jdbc.execute("SET LOCAL statement_timeout='4s'")
            jdbc.queryForList(capture(sql), 2)
            jdbc.update(
                match<String> { insert -> insert.startsWith("INSERT INTO pawbridge_animal.animal_search_documents") },
                *anyVararg<Any>(),
            )
            jdbc.update("UPDATE pawbridge_animal.animals SET search_dirty=false WHERE id=?", 42L)
        }
        assertThat(sql.captured).contains(
            "FROM pawbridge_animal.animals a WHERE search_dirty",
            "ORDER BY id LIMIT ? FOR UPDATE OF a SKIP LOCKED",
        ).doesNotContain("NOT EXISTS", "analyzer_version")
        verify(exactly = 1) { analyzer.animalTerms("믹스견", "갈색", "귀", "순함", "서울") }
        assertThat(callbacks).isEqualTo(1)
    }

    @Test
    fun `동물 무결성 감사에서 잠기지 않은 행이 없을 때__갱신하면__리비전과 버전을 확인하고 0을 돌려준다`() {
        // given
        every { transaction.execute(any<TransactionCallback<Any>>()) } answers {
            runCallback(firstArg<TransactionCallback<*>>())
        }
        every { jdbc.execute(any<String>()) } answers { assertCallbackActive() }
        every { jdbc.queryForList(any<String>(), *anyVararg<Any>()) } answers {
            assertCallbackActive()
            listOf<Map<String, Any>>()
        }

        // when
        val refreshed = projector.refreshAnimals(100)

        // then
        val sql = slot<String>()
        assertThat(refreshed).isZero()
        verify(exactly = 1) { jdbc.queryForList(capture(sql), KoreanSearchAnalyzer.VERSION, 100) }
        assertThat(sql.captured).contains(
            "search_dirty OR NOT EXISTS (SELECT 1 FROM pawbridge_animal.animal_search_documents d",
            "d.animal_id=a.id AND d.source_revision=a.search_revision AND d.analyzer_version=?",
            "ORDER BY id LIMIT ? FOR UPDATE OF a SKIP LOCKED",
        )
        verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg<Any>()) }
        confirmVerified(analyzer)
        assertThat(callbacks).isEqualTo(1)
    }

    @Test
    fun `변경 표시된 보호소가 있을 때__갱신하면__원본을 잠그고 기존 보호소 작성기를 재사용한다`() {
        // given
        every { transaction.execute(any<TransactionCallback<Any>>()) } answers {
            runCallback(firstArg<TransactionCallback<*>>())
        }
        every { jdbc.execute(any<String>()) } answers { assertCallbackActive() }
        every { jdbc.queryForList(any<String>(), *anyVararg<Any>()) } answers {
            assertCallbackActive()
            listOf(mapOf("id" to 7L, "search_revision" to 3L, "name" to "서울 센터", "address" to "강남"))
        }
        every { analyzer.shelterTerms(any(), any()) } returns KoreanSearchAnalyzer.ShelterTerms("서울 센터", "강남")
        every { jdbc.update(any<String>(), *anyVararg<Any>()) } returns 0

        // when
        val refreshed = projector.refreshDirtyShelters(1)

        // then
        val sql = slot<String>()
        assertThat(refreshed).isEqualTo(1)
        verifyOrder {
            jdbc.execute("SET LOCAL lock_timeout='1s'")
            jdbc.execute("SET LOCAL statement_timeout='4s'")
            jdbc.queryForList(capture(sql), 1)
            jdbc.update(
                match<String> { insert -> insert.startsWith("INSERT INTO pawbridge_animal.shelter_search_documents") },
                *anyVararg<Any>(),
            )
            jdbc.update("UPDATE pawbridge_animal.shelters SET search_dirty=false WHERE id=?", 7L)
        }
        assertThat(sql.captured).contains(
            "FROM pawbridge_animal.shelters s WHERE search_dirty",
            "ORDER BY id LIMIT ? FOR UPDATE OF s SKIP LOCKED",
        ).doesNotContain("NOT EXISTS", "analyzer_version")
        verify(exactly = 1) { analyzer.shelterTerms("서울 센터", "강남") }
        assertThat(callbacks).isEqualTo(1)
    }

    @Test
    fun `보호소 무결성 감사에서 잠기지 않은 행이 없을 때__갱신하면__쓰기 없이 리비전과 버전을 확인한다`() {
        // given
        every { transaction.execute(any<TransactionCallback<Any>>()) } answers {
            runCallback(firstArg<TransactionCallback<*>>())
        }
        every { jdbc.execute(any<String>()) } answers { assertCallbackActive() }
        every { jdbc.queryForList(any<String>(), *anyVararg<Any>()) } answers {
            assertCallbackActive()
            listOf<Map<String, Any>>()
        }

        // when
        val refreshed = projector.refreshShelters(50)

        // then
        val sql = slot<String>()
        assertThat(refreshed).isZero()
        verify(exactly = 1) { jdbc.queryForList(capture(sql), KoreanSearchAnalyzer.VERSION, 50) }
        assertThat(sql.captured).contains(
            "search_dirty OR NOT EXISTS (SELECT 1 FROM pawbridge_animal.shelter_search_documents d",
            "d.shelter_id=s.id AND d.source_revision=s.search_revision AND d.analyzer_version=?",
            "ORDER BY id LIMIT ? FOR UPDATE OF s SKIP LOCKED",
        )
        verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg<Any>()) }
        confirmVerified(analyzer)
        assertThat(callbacks).isEqualTo(1)
    }

    @Test
    fun `투영 쓰기가 실패할 때__갱신하면__변경 표시를 지우지 않고 같은 예외를 전파한다`() {
        // given
        val failure = DataAccessResourceFailureException("synthetic write failure")

        every { transaction.execute(any<TransactionCallback<Any>>()) } answers {
            runCallback(firstArg<TransactionCallback<*>>())
        }
        every { jdbc.execute(any<String>()) } answers { assertCallbackActive() }
        every { jdbc.queryForList(any<String>(), *anyVararg<Any>()) } answers {
            assertCallbackActive()
            listOf(animalRow())
        }
        every { analyzer.animalTerms(any(), any(), any(), any(), any()) } returns
            KoreanSearchAnalyzer.AnimalTerms("귀", "믹스견", "갈색", "순함", "서울", "관계")
        every {
            jdbc.update(
                match<String> { insert -> insert.startsWith("INSERT INTO pawbridge_animal.animal_search_documents") },
                *anyVararg<Any>(),
            )
        } throws failure

        // when
        val thrown = assertThatThrownBy { projector.refreshDirtyAnimals(1) }

        // then
        thrown.isSameAs(failure)
        verify(exactly = 0) { jdbc.update("UPDATE pawbridge_animal.animals SET search_dirty=false WHERE id=?", 42L) }
        assertThat(callbackActive).isFalse()
        assertThat(callbacks).isEqualTo(1)
    }

    @Test
    fun `두 원본 종류를 차례로 갱신할 때__갱신하면__트랜잭션 콜백을 각각 따로 쓴다`() {
        // given
        every { transaction.execute(any<TransactionCallback<Any>>()) } answers {
            runCallback(firstArg<TransactionCallback<*>>())
        }
        every { jdbc.execute(any<String>()) } answers { assertCallbackActive() }
        every { jdbc.queryForList(any<String>(), *anyVararg<Any>()) } answers {
            assertCallbackActive()
            listOf<Map<String, Any>>()
        }

        // when
        val shelters = projector.refreshDirtyShelters(50)
        val animals = projector.refreshDirtyAnimals(50)

        // then
        assertThat(shelters).isZero()
        assertThat(animals).isZero()
        assertThat(callbacks).isEqualTo(2)
        assertThat(callbackActive).isFalse()
    }

    private fun runCallback(callback: TransactionCallback<*>): Any? {
        assertThat(callbackActive).isFalse()
        callbackActive = true
        callbacks++
        try {
            return callback.doInTransaction(SimpleTransactionStatus())
        } finally {
            callbackActive = false
        }
    }

    private fun assertCallbackActive() {
        assertThat(callbackActive).isTrue()
    }

    private fun refreshMethods(): List<IntUnaryOperator> {
        return listOf(
            IntUnaryOperator { limit -> projector.refreshAnimals(limit) },
            IntUnaryOperator { limit -> projector.refreshDirtyAnimals(limit) },
            IntUnaryOperator { limit -> projector.refreshShelters(limit) },
            IntUnaryOperator { limit -> projector.refreshDirtyShelters(limit) },
        )
    }

    private fun animalRow(): Map<String, Any> {
        return mapOf(
            "id" to 42L,
            "search_revision" to 9L,
            "breed" to "믹스견",
            "color" to "갈색",
            "special_mark" to "귀",
            "description" to "순함",
            "happen_place" to "서울",
        )
    }
}
