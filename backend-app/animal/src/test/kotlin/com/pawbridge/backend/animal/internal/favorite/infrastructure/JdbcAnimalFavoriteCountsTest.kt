package com.pawbridge.backend.animal.internal.favorite.infrastructure

import com.pawbridge.backend.animal.internal.favorite.infrastructure.JdbcAnimalFavoriteCounts.Companion.DECREMENT
import com.pawbridge.backend.animal.internal.favorite.infrastructure.JdbcAnimalFavoriteCounts.Companion.INCREMENT
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.support.SqlValue
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar
import java.util.function.Supplier

class JdbcAnimalFavoriteCountsTest {
    private val jdbc = mockk<JdbcOperations>()
    private val counts = JdbcAnimalFavoriteCounts(jdbc, Supplier { NOW })

    @Test
    fun `기존 동물일 때__좋아요 수를 올리면__한 문장으로 수와 UTC updated_at을 갱신한다`() {
        // given
        every { jdbc.update(any<String>(), any<SqlValue>(), any<Long>()) } returns 1

        // when
        val adjusted = counts.adjust(7, 1)

        // then
        assertThat(adjusted).isTrue()
        val time = slot<SqlValue>()
        verify(exactly = 1) { jdbc.update(INCREMENT, capture(time), 7L) }
        confirmVerified(jdbc)

        // given
        val statement = mockk<PreparedStatement>()

        justRun { statement.setTimestamp(any(), any(), any<Calendar>()) }

        // when
        time.captured.setValue(statement, 1)

        // then
        val calendar = slot<Calendar>()
        verify(exactly = 1) { statement.setTimestamp(1, Timestamp.valueOf(NOW), capture(calendar)) }
        confirmVerified(statement)
        assertThat(calendar.captured.timeZone.id).isEqualTo("UTC")
    }

    @Test
    fun `기존 동물일 때__좋아요 수를 내리면__0 밑으로 내려가지 않는 문장을 쓴다`() {
        // given
        every { jdbc.update(any<String>(), any<SqlValue>(), any<Long>()) } returns 1

        // when
        val adjusted = counts.adjust(7, -1)

        // then
        assertThat(adjusted).isTrue()
        verify(exactly = 1) { jdbc.update(DECREMENT, any<SqlValue>(), 7L) }
        assertThat(DECREMENT).contains("CASE WHEN favorite_count>0 THEN favorite_count-1 ELSE favorite_count END")
            .contains("updated_at=CASE WHEN favorite_count>0 THEN ? ELSE updated_at END")
    }

    @ParameterizedTest
    @ValueSource(ints = [1, -1])
    fun `일치하는 동물이 없을 때__좋아요 수를 조정하면__실패하지 않고 없음을 알린다`(delta: Int) {
        // given
        every { jdbc.update(any<String>(), any<SqlValue>(), any<Long>()) } returns 0

        // when
        val adjusted = counts.adjust(7, delta)

        // then
        assertThat(adjusted).isFalse()
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 2, -2])
    fun `지원하지 않는 증감값일 때__좋아요 수를 조정하면__SQL 전에 거부한다`(delta: Int) {
        // given

        // when
        val thrown = assertThatThrownBy { counts.adjust(7, delta) }

        // then
        thrown.isInstanceOf(IllegalArgumentException::class.java)
        confirmVerified(jdbc)
    }

    @Test
    fun `불가능한 행 수가 돌아올 때__좋아요 수를 조정하면__성공으로 보고하지 않고 실패한다`() {
        // given
        every { jdbc.update(any<String>(), any<SqlValue>(), any<Long>()) } returns 2

        // when
        val thrown = assertThatThrownBy { counts.adjust(7, 1) }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java)
    }

    companion object {
        private val NOW: LocalDateTime = LocalDateTime.of(2032, 1, 2, 3, 4, 5, 123456000)
    }
}
