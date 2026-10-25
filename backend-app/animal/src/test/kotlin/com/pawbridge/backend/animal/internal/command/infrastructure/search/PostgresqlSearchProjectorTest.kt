package com.pawbridge.backend.animal.internal.command.infrastructure.search

import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.startsWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.function.IntUnaryOperator

class PostgresqlSearchProjectorTest {
    private val jdbc: JdbcTemplate = mock(JdbcTemplate::class.java)
    private val transaction: TransactionTemplate = mock(TransactionTemplate::class.java)
    private val analyzer: KoreanSearchAnalyzer = mock(KoreanSearchAnalyzer::class.java)
    private val projector = PostgresqlSearchProjector(jdbc, transaction, analyzer)
    private var callbackActive = false
    private var callbacks = 0

    @BeforeEach
    fun executeOnlyTheSuppliedTransactionCallback() {
        doAnswer { call ->
            assertThat(callbackActive).isFalse()
            callbackActive = true
            callbacks++
            try {
                val callback: TransactionCallback<*> = call.getArgument(0)
                callback.doInTransaction(SimpleTransactionStatus())
            } finally {
                callbackActive = false
            }
        }.`when`(transaction).execute(any<TransactionCallback<Any>>())
        doAnswer {
            assertThat(callbackActive).isTrue()
            null
        }.`when`(jdbc).execute(anyString())
    }

    @AfterEach
    fun clearOnlyTheTransactionMarkerSetByThisTest() {
        TransactionSynchronizationManager.setActualTransactionActive(false)
    }

    @Test
    fun givenInvalidLimits__whenAnyRefreshIsRequested__thenRejectBeforeTransactionOrSql() {
        for (refresh in refreshMethods()) {
            for (limit in intArrayOf(-1, 0, 101)) {
                assertThatThrownBy { refresh.applyAsInt(limit) }.isInstanceOf(IllegalArgumentException::class.java)
            }
        }

        verifyNoInteractions(transaction, jdbc, analyzer)
    }

    @Test
    fun givenAnActualCallerTransaction__whenAnyRefreshIsRequested__thenRejectWithoutNestedTransactionOrSql() {
        TransactionSynchronizationManager.setActualTransactionActive(true)

        for (refresh in refreshMethods()) {
            assertThatThrownBy { refresh.applyAsInt(50) }.isInstanceOf(IllegalStateException::class.java)
        }

        verifyNoInteractions(transaction, jdbc, analyzer)
    }

    @Test
    fun givenDirtyAnimals__whenRefreshed__thenLockOneBoundedPageAndReuseTheExistingWriterInsideTheCallback() {
        rows(listOf(animalRow()))
        animalTerms()

        assertThat(projector.refreshDirtyAnimals(2)).isEqualTo(1)

        val sql = ArgumentCaptor.forClass(String::class.java)
        val order = inOrder(jdbc)
        order.verify(jdbc).execute("SET LOCAL lock_timeout='1s'")
        order.verify(jdbc).execute("SET LOCAL statement_timeout='4s'")
        order.verify(jdbc).queryForList(sql.capture(), eq(2))
        assertThat(sql.value).contains(
            "FROM pawbridge_animal.animals a WHERE search_dirty",
            "ORDER BY id LIMIT ? FOR UPDATE OF a SKIP LOCKED",
        ).doesNotContain("NOT EXISTS", "analyzer_version")
        order.verify(jdbc).update(startsWith("INSERT INTO pawbridge_animal.animal_search_documents"), any(Array<Any>::class.java))
        order.verify(jdbc).update("UPDATE pawbridge_animal.animals SET search_dirty=false WHERE id=?", 42L)
        verify(analyzer).animalTerms("믹스견", "갈색", "귀", "순함", "서울")
        assertThat(callbacks).isEqualTo(1)
    }

    @Test
    fun givenAnimalIntegrityAudit__whenNoUnlockedRowsAreReturned__thenCheckRevisionAndVersionAndReturnZero() {
        rows(listOf())

        assertThat(projector.refreshAnimals(100)).isZero()

        val sql = ArgumentCaptor.forClass(String::class.java)
        verify(jdbc).queryForList(sql.capture(), eq(KoreanSearchAnalyzer.VERSION), eq(100))
        assertThat(sql.value).contains(
            "search_dirty OR NOT EXISTS (SELECT 1 FROM pawbridge_animal.animal_search_documents d",
            "d.animal_id=a.id AND d.source_revision=a.search_revision AND d.analyzer_version=?",
            "ORDER BY id LIMIT ? FOR UPDATE OF a SKIP LOCKED",
        )
        verify(jdbc, never()).update(anyString(), any(Array<Any>::class.java))
        verifyNoInteractions(analyzer)
        assertThat(callbacks).isEqualTo(1)
    }

    @Test
    fun givenDirtyShelters__whenRefreshed__thenLockTheSourceAndReuseTheExistingShelterWriter() {
        rows(listOf(mapOf("id" to 7L, "search_revision" to 3L, "name" to "서울 센터", "address" to "강남")))
        `when`(analyzer.shelterTerms("서울 센터", "강남"))
            .thenReturn(KoreanSearchAnalyzer.ShelterTerms("서울 센터", "강남"))

        assertThat(projector.refreshDirtyShelters(1)).isEqualTo(1)

        val sql = ArgumentCaptor.forClass(String::class.java)
        val order = inOrder(jdbc)
        order.verify(jdbc).execute("SET LOCAL lock_timeout='1s'")
        order.verify(jdbc).execute("SET LOCAL statement_timeout='4s'")
        order.verify(jdbc).queryForList(sql.capture(), eq(1))
        assertThat(sql.value).contains(
            "FROM pawbridge_animal.shelters s WHERE search_dirty",
            "ORDER BY id LIMIT ? FOR UPDATE OF s SKIP LOCKED",
        ).doesNotContain("NOT EXISTS", "analyzer_version")
        order.verify(jdbc).update(startsWith("INSERT INTO pawbridge_animal.shelter_search_documents"), any(Array<Any>::class.java))
        order.verify(jdbc).update("UPDATE pawbridge_animal.shelters SET search_dirty=false WHERE id=?", 7L)
        assertThat(callbacks).isEqualTo(1)
    }

    @Test
    fun givenShelterIntegrityAudit__whenNoUnlockedRowsAreReturned__thenCheckRevisionAndVersionWithoutWriting() {
        rows(listOf())

        assertThat(projector.refreshShelters(50)).isZero()

        val sql = ArgumentCaptor.forClass(String::class.java)
        verify(jdbc).queryForList(sql.capture(), eq(KoreanSearchAnalyzer.VERSION), eq(50))
        assertThat(sql.value).contains(
            "search_dirty OR NOT EXISTS (SELECT 1 FROM pawbridge_animal.shelter_search_documents d",
            "d.shelter_id=s.id AND d.source_revision=s.search_revision AND d.analyzer_version=?",
            "ORDER BY id LIMIT ? FOR UPDATE OF s SKIP LOCKED",
        )
        verify(jdbc, never()).update(anyString(), any(Array<Any>::class.java))
        verifyNoInteractions(analyzer)
        assertThat(callbacks).isEqualTo(1)
    }

    @Test
    fun givenAProjectionWriteFailure__whenRefreshed__thenPropagateTheSameFailureWithoutClearingDirty() {
        rows(listOf(animalRow()))
        animalTerms()
        val failure = DataAccessResourceFailureException("synthetic write failure")
        doThrow(failure).`when`(jdbc)
            .update(startsWith("INSERT INTO pawbridge_animal.animal_search_documents"), any(Array<Any>::class.java))

        assertThatThrownBy { projector.refreshDirtyAnimals(1) }.isSameAs(failure)

        verify(jdbc, never()).update("UPDATE pawbridge_animal.animals SET search_dirty=false WHERE id=?", 42L)
        assertThat(callbackActive).isFalse()
        assertThat(callbacks).isEqualTo(1)
    }

    @Test
    fun givenBothSourceTypes__whenRefreshedSequentially__thenUseSeparateTransactionCallbacks() {
        rows(listOf())

        assertThat(projector.refreshDirtyShelters(50)).isZero()
        assertThat(projector.refreshDirtyAnimals(50)).isZero()

        assertThat(callbacks).isEqualTo(2)
        assertThat(callbackActive).isFalse()
    }

    private fun refreshMethods(): List<IntUnaryOperator> {
        return listOf(
            IntUnaryOperator { limit -> projector.refreshAnimals(limit) },
            IntUnaryOperator { limit -> projector.refreshDirtyAnimals(limit) },
            IntUnaryOperator { limit -> projector.refreshShelters(limit) },
            IntUnaryOperator { limit -> projector.refreshDirtyShelters(limit) },
        )
    }

    private fun rows(selected: List<Map<String, Any?>>) {
        `when`(jdbc.queryForList(anyString(), any(Array<Any>::class.java))).thenAnswer {
            assertThat(callbackActive).isTrue()
            selected
        }
    }

    private fun animalRow(): Map<String, Any?> {
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

    private fun animalTerms() {
        `when`(analyzer.animalTerms("믹스견", "갈색", "귀", "순함", "서울"))
            .thenReturn(KoreanSearchAnalyzer.AnimalTerms("귀", "믹스견", "갈색", "순함", "서울", "관계"))
    }
}
