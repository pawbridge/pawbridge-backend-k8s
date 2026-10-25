package com.pawbridge.backend.animal.internal.command.infrastructure.search

import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.jdbc.core.JdbcTemplate
import java.io.IOException
import java.io.UncheckedIOException

class ShelterSearchProjectionTest {
    private val jdbc: JdbcTemplate = mock(JdbcTemplate::class.java)
    private val analyzer: KoreanSearchAnalyzer = mock(KoreanSearchAnalyzer::class.java)
    private val row: Map<String, Any> = mapOf(
        "id" to 7L,
        "search_revision" to 3L,
        "name" to "서울 센터",
        "address" to "강남",
    )

    @Test
    fun givenShelterTerms__whenProjecting__thenUseOriginalWeightRevisionAndClearOnlyItsDirtyFlag() {
        `when`(analyzer.shelterTerms("서울 센터", "강남")).thenReturn(KoreanSearchAnalyzer.ShelterTerms("서울 센터", "강남"))

        SearchDocumentWriter.writeShelter(jdbc, analyzer, row)

        verify(jdbc).update(
            contains("INSERT INTO pawbridge_animal.shelter_search_documents"),
            eq(7L),
            eq(3L),
            eq(KoreanSearchAnalyzer.VERSION),
            eq("서울 센터"),
            eq("강남"),
        )
        verify(jdbc).update("UPDATE pawbridge_animal.shelters SET search_dirty=false WHERE id=?", 7L)
    }

    @Test
    fun givenAnalyzerFailure__whenProjecting__thenWriteNothingAndPropagateForWriterRollback() {
        `when`(analyzer.shelterTerms(any(), any())).thenThrow(IOException("analyzer"))

        assertThatThrownBy { SearchDocumentWriter.writeShelter(jdbc, analyzer, row) }
            .isInstanceOf(UncheckedIOException::class.java)
        verifyNoInteractions(jdbc)
    }
}
