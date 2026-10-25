package com.pawbridge.backend.animal.internal.command.infrastructure.search

import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

class SearchDocumentWriterTest {
    private val source = mockk<DataSource>()

    @AfterEach
    fun clearTransactionState() {
        TransactionSynchronizationManager.clear()
        if (TransactionSynchronizationManager.hasResource(source)) {
            TransactionSynchronizationManager.unbindResource(source)
        }
    }

    @Test
    fun `실제 트랜잭션이 없을 때__동물 문서를 쓰면__연결을 빌리기 전에 거부한다`() {
        KoreanSearchAnalyzer().use { analyzer ->
            // given
            val writer = SearchDocumentWriter(source, analyzer)

            // when
            val thrown = assertThatThrownBy { writer.animal(42L) }

            // then
            thrown.isInstanceOf(IllegalStateException::class.java)
            confirmVerified(source)
        }
    }

    @Test
    fun `다른 데이터 소스의 트랜잭션일 때__투영을 쓰면__거부한다`() {
        KoreanSearchAnalyzer().use { analyzer ->
            // given
            TransactionSynchronizationManager.setActualTransactionActive(true)
            val writer = SearchDocumentWriter(source, analyzer)

            // when
            val thrown = assertThatThrownBy { writer.animal(42L) }

            // then
            thrown.isInstanceOf(IllegalStateException::class.java)
            confirmVerified(source)
        }
    }

    @Test
    fun `읽기 전용 트랜잭션일 때__투영을 쓰면__거부한다`() {
        KoreanSearchAnalyzer().use { analyzer ->
            // given
            TransactionSynchronizationManager.setActualTransactionActive(true)
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)
            TransactionSynchronizationManager.bindResource(source, Any())
            excludeRecords { source.hashCode() }
            val writer = SearchDocumentWriter(source, analyzer)

            // when
            val thrown = assertThatThrownBy { writer.animal(42L) }

            // then
            thrown.isInstanceOf(IllegalStateException::class.java)
            confirmVerified(source)
        }
    }

    @Test
    fun `기존 분석기 용어가 있을 때__동물 문서를 쓰면__동물 스키마에 쓰고 그 뒤에 변경 표시를 지운다`() {
        // given
        val jdbc = mockk<JdbcTemplate>()
        val animalRow = mapOf(
            "id" to 42L,
            "search_revision" to 9L,
            "breed" to "믹스견",
            "color" to "갈색",
            "special_mark" to "귀가 접힌 강아지",
            "description" to "순해요",
            "happen_place" to "서울",
        )

        every { jdbc.update(any<String>(), *anyVararg<Any>()) } returns 1

        // when
        KoreanSearchAnalyzer().use { analyzer ->
            SearchDocumentWriter.writeAnimal(jdbc, analyzer, animalRow)
        }

        // then
        verifyOrder {
            jdbc.update(
                match<String> { sql -> sql.startsWith("INSERT INTO pawbridge_animal.animal_search_documents") },
                42L,
                9L,
                KoreanSearchAnalyzer.VERSION,
                any<String>(),
                "믹스견",
                "갈색",
                any<String>(),
                any<String>(),
                "믹스견",
                any<String>(),
            )
            jdbc.update("UPDATE pawbridge_animal.animals SET search_dirty=false WHERE id=?", 42L)
        }
    }
}
