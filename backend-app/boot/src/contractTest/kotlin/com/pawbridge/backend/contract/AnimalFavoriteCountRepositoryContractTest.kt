package com.pawbridge.backend.contract

import com.pawbridge.backend.animal.internal.favorite.infrastructure.JdbcAnimalFavoriteCounts
import java.time.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

/** The favorite counter update on the real animals table: +1, -1 that stops at zero, and unknown animals. */
class AnimalFavoriteCountRepositoryContractTest : AnimalRepositoryContractBase() {
    private lateinit var counts: JdbcAnimalFavoriteCounts

    @BeforeEach
    fun seed() {
        counts = JdbcAnimalFavoriteCounts(JdbcTemplate(writeSource)) { LocalDateTime.of(2026, 3, 5, 1, 2, 3, 456_789_000) }
        seedShelter(1)
        seedAnimal(10, 1, favoriteCount = 1)
        seedAnimal(11, 1, favoriteCount = 0)
        execute("UPDATE pawbridge_animal.animals SET updated_at = '2025-01-01 00:00:00'")
    }

    @Test
    fun `동물이 있을 때__늘리면__수를 하나 올리고 수정 시각을 쓴다`() {
        // given
        // when
        val changed = counts.adjust(10, 1)

        // then
        assertThat(changed).isTrue()
        assertThat(scalar("SELECT favorite_count || '|' || updated_at FROM pawbridge_animal.animals WHERE id = 10")).isEqualTo("2|2026-03-05 01:02:03.456789")
        assertThat(scalar("SELECT updated_at FROM pawbridge_animal.animals WHERE id = 11")).`as`("Other animals are untouched").isEqualTo("2025-01-01 00:00:00")
    }

    @Test
    fun `수가 1이거나 0일 때__줄이면__1이면 0으로 내리고 0이면 수도 수정 시각도 그대로다`() {
        // given
        // when
        val down = counts.adjust(10, -1)
        val atZero = counts.adjust(11, -1)

        // then
        assertThat(down).isTrue()
        assertThat(atZero).`as`("The row exists, so one row matched").isTrue()
        assertThat(scalar("SELECT favorite_count || '|' || updated_at FROM pawbridge_animal.animals WHERE id = 10")).isEqualTo("0|2026-03-05 01:02:03.456789")
        assertThat(scalar("SELECT favorite_count || '|' || updated_at FROM pawbridge_animal.animals WHERE id = 11")).isEqualTo("0|2025-01-01 00:00:00")
    }

    @Test
    fun `없는 동물이거나 허용되지 않은 증감일 때__조정하면__거짓이거나 예외이고 아무것도 바뀌지 않는다`() {
        // given
        // when
        // then
        assertNoSideEffects {
            assertThat(counts.adjust(999, 1)).isFalse()
            assertThatThrownBy { counts.adjust(10, 2) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { counts.adjust(10, 0) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
