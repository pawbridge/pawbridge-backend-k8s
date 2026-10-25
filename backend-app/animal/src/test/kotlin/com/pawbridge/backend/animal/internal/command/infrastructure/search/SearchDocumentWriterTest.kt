package com.pawbridge.backend.animal.internal.command.infrastructure.search

import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.startsWith
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

class SearchDocumentWriterTest {
    private val source: DataSource = mock(DataSource::class.java)

    @AfterEach
    fun clearTransactionState() {
        TransactionSynchronizationManager.clear()
        if (TransactionSynchronizationManager.hasResource(source)) {
            TransactionSynchronizationManager.unbindResource(source)
        }
    }

    @Test
    fun requires_an_actual_transaction_before_borrowing_a_connection() {
        KoreanSearchAnalyzer().use { analyzer ->
            assertThatThrownBy { SearchDocumentWriter(source, analyzer).animal(42L) }
                .isInstanceOf(IllegalStateException::class.java)
            verifyNoInteractions(source)
        }
    }

    @Test
    fun transaction_on_another_data_source_cannot_write_projection() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
        KoreanSearchAnalyzer().use { analyzer ->
            assertThatThrownBy { SearchDocumentWriter(source, analyzer).animal(42L) }
                .isInstanceOf(IllegalStateException::class.java)
            verifyNoInteractions(source)
        }
    }

    @Test
    fun read_only_transaction_cannot_write_projection() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)
        TransactionSynchronizationManager.bindResource(source, Any())
        KoreanSearchAnalyzer().use { analyzer ->
            assertThatThrownBy { SearchDocumentWriter(source, analyzer).animal(42L) }
                .isInstanceOf(IllegalStateException::class.java)
            verifyNoInteractions(source)
        }
    }

    @Test
    fun writes_existing_analyzer_terms_to_animal_schema_before_clearing_dirty_flag() {
        val jdbc = mock(JdbcTemplate::class.java)
        KoreanSearchAnalyzer().use { analyzer ->
            SearchDocumentWriter.writeAnimal(
                jdbc,
                analyzer,
                mapOf(
                    "id" to 42L,
                    "search_revision" to 9L,
                    "breed" to "믹스견",
                    "color" to "갈색",
                    "special_mark" to "귀가 접힌 강아지",
                    "description" to "순해요",
                    "happen_place" to "서울",
                ),
            )
        }

        val order = inOrder(jdbc)
        order.verify(jdbc).update(
            startsWith("INSERT INTO pawbridge_animal.animal_search_documents"),
            eq(42L),
            eq(9L),
            eq(KoreanSearchAnalyzer.VERSION),
            anyString(),
            eq("믹스견"),
            eq("갈색"),
            anyString(),
            anyString(),
            eq("믹스견"),
            anyString(),
        )
        order.verify(jdbc).update("UPDATE pawbridge_animal.animals SET search_dirty=false WHERE id=?", 42L)
    }
}
