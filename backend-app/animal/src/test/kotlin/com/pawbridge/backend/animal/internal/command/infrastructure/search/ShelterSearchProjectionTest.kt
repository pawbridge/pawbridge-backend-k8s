package com.pawbridge.backend.animal.internal.command.infrastructure.search

import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.io.IOException
import java.io.UncheckedIOException

class ShelterSearchProjectionTest {
    private val jdbc = mockk<JdbcTemplate>()
    private val analyzer = mockk<KoreanSearchAnalyzer>()
    private val row: Map<String, Any> = mapOf(
        "id" to 7L,
        "search_revision" to 3L,
        "name" to "서울 센터",
        "address" to "강남",
    )

    @Test
    fun `보호소 검색어가 있을 때__보호소를 투영하면__원문 가중치와 리비전을 쓰고 자기 dirty 플래그만 내린다`() {
        // given
        every { analyzer.shelterTerms(any(), any()) } returns KoreanSearchAnalyzer.ShelterTerms("서울 센터", "강남")
        every { jdbc.update(any<String>(), *anyVararg()) } returns 1

        // when
        SearchDocumentWriter.writeShelter(jdbc, analyzer, row)

        // then
        verify(exactly = 1) { analyzer.shelterTerms("서울 센터", "강남") }
        verify(exactly = 1) {
            jdbc.update(
                match<String> { sql -> sql.contains("INSERT INTO pawbridge_animal.shelter_search_documents") },
                7L,
                3L,
                KoreanSearchAnalyzer.VERSION,
                "서울 센터",
                "강남",
            )
        }
        verify(exactly = 1) {
            jdbc.update("UPDATE pawbridge_animal.shelters SET search_dirty=false WHERE id=?", 7L)
        }
        confirmVerified(jdbc, analyzer)
    }

    @Test
    fun `분석기가 실패할 때__보호소를 투영하면__아무것도 쓰지 않고 작성자 롤백을 위해 전파한다`() {
        // given
        every { analyzer.shelterTerms(any(), any()) } throws IOException("analyzer")

        // when
        val thrown = assertThatThrownBy { SearchDocumentWriter.writeShelter(jdbc, analyzer, row) }

        // then
        thrown.isInstanceOf(UncheckedIOException::class.java)
        confirmVerified(jdbc)
    }
}
