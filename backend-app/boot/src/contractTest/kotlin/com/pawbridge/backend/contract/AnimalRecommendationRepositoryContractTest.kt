package com.pawbridge.backend.contract

import com.pawbridge.backend.animal.internal.discovery.infrastructure.JdbcAnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations
import com.pawbridge.backend.animal.internal.recommendation.application.RecommendationUnavailableException
import com.pawbridge.backend.animal.internal.recommendation.infrastructure.JdbcAnimalRecommendations
import com.pawbridge.backend.animal.internal.recommendation.infrastructure.PythonAnimalRecommendations
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The similar-animal cards: the source animal and the candidate rows are read from the real tables, Python is a mock. */
class AnimalRecommendationRepositoryContractTest : AnimalRepositoryContractBase() {
    private val analyzer = KoreanSearchAnalyzer()
    private val python = mockk<PythonAnimalRecommendations>()
    private lateinit var recommendations: AnimalRecommendations

    @BeforeEach
    fun build() {
        recommendations = JdbcAnimalRecommendations(JdbcAnimalDiscovery(source, manager, analyzer), python, source, manager)
        seedShelter(1)
        seedAnimal(10, 1, species = "DOG")
        seedAnimal(11, 1, species = "DOG", status = "NOTICE", breed = "푸들")
        seedAnimal(12, 1, species = "DOG", status = "PROTECT")
        seedAnimal(13, 1, species = "CAT")
        seedAnimal(14, 1, species = "DOG", status = "ADOPTED")
        seedAnimal(20, 1, species = "ETC")
    }

    @AfterEach
    fun close() {
        analyzer.close()
    }

    @Test
    fun `파이썬이 후보 번호를 줄 때__추천하면__파이썬 순서를 지키고 자기 자신과 다른 종과 종료된 동물과 없는 번호를 뺀다`() {
        // given
        every { python.recommend(10, "DOG") } returns listOf(12L, 10L, 13L, 14L, 11L, 999L)

        // when
        val cards = recommendations.recommend(10)

        // then
        assertThat(cards.map { it.id }).isEqualTo(listOf(12L, 11L))
        assertThat(cards.last().breed).isEqualTo("푸들")
        assertThat(cards.last().shelterName).isEqualTo("보호소 1")
    }

    @Test
    fun `기준 동물이 개나 고양이가 아니거나 후보가 비었을 때__추천하면__빈 목록이고 기준 동물이 없으면 없음 예외다`() {
        // given
        every { python.recommend(10, "DOG") } returns listOf(10L)

        // when
        // then
        assertThat(recommendations.recommend(20)).isEmpty()
        verify(exactly = 0) { python.recommend(20, any()) }
        assertThat(recommendations.recommend(10)).isEmpty()
        assertThatThrownBy { recommendations.recommend(999) }.hasMessageContaining("동물을 찾을 수 없습니다")
    }

    @Test
    fun `파이썬이 실패하거나 잘못된 번호를 줄 때__추천하면__준비 중 예외다`() {
        // given
        every { python.recommend(10, "DOG") } throws IllegalStateException("down") andThen listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L) andThen listOf(-1L)

        // when
        // then
        repeat(3) {
            assertThatThrownBy { recommendations.recommend(10) }.isInstanceOf(RecommendationUnavailableException::class.java)
        }
    }
}
