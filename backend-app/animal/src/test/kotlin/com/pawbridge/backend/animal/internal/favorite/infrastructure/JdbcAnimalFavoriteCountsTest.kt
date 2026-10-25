package com.pawbridge.backend.animal.internal.favorite.infrastructure

import com.pawbridge.backend.animal.internal.favorite.infrastructure.JdbcAnimalFavoriteCounts.Companion.DECREMENT
import com.pawbridge.backend.animal.internal.favorite.infrastructure.JdbcAnimalFavoriteCounts.Companion.INCREMENT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.support.SqlValue
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar
import java.util.function.Supplier

class JdbcAnimalFavoriteCountsTest {
    private val jdbc: JdbcOperations = mock(JdbcOperations::class.java)
    private val counts = JdbcAnimalFavoriteCounts(jdbc, Supplier { NOW })

    @Test
    fun given__an_existing_animal__when__incrementing__then__update_count_and_utc_updated_at_in_one_statement() {
        `when`(jdbc.update(eq(INCREMENT), any(SqlValue::class.java), eq(7L))).thenReturn(1)

        assertThat(counts.adjust(7, 1)).isTrue()

        val time = ArgumentCaptor.forClass(SqlValue::class.java)
        verify(jdbc).update(eq(INCREMENT), time.capture(), eq(7L))
        val statement = mock(PreparedStatement::class.java)
        time.value.setValue(statement, 1)
        val calendar = ArgumentCaptor.forClass(Calendar::class.java)
        verify(statement).setTimestamp(eq(1), eq(Timestamp.valueOf(NOW)), calendar.capture())
        assertThat(calendar.value.timeZone.id).isEqualTo("UTC")
        verifyNoMoreInteractions(jdbc)
    }

    @Test
    fun given__an_existing_animal__when__decrementing__then__use_the_floor_at_zero_statement() {
        `when`(jdbc.update(eq(DECREMENT), any(SqlValue::class.java), eq(7L))).thenReturn(1)

        assertThat(counts.adjust(7, -1)).isTrue()

        assertThat(DECREMENT).contains("CASE WHEN favorite_count>0 THEN favorite_count-1 ELSE favorite_count END")
            .contains("updated_at=CASE WHEN favorite_count>0 THEN ? ELSE updated_at END")
    }

    @ParameterizedTest
    @ValueSource(ints = [1, -1])
    fun given__no_matching_animal__when__adjusting__then__report_absence_without_failing(delta: Int) {
        `when`(jdbc.update(anyString(), any(SqlValue::class.java), anyLong())).thenReturn(0)

        assertThat(counts.adjust(7, delta)).isFalse()
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 2, -2])
    fun given__an_unsupported_delta__when__adjusting__then__reject_before_any_sql(delta: Int) {
        assertThatIllegalArgumentException().isThrownBy { counts.adjust(7, delta) }

        verifyNoInteractions(jdbc)
    }

    @Test
    fun given__an_impossible_row_count__when__adjusting__then__fail_instead_of_reporting_success() {
        `when`(jdbc.update(anyString(), any(SqlValue::class.java), anyLong())).thenReturn(2)

        assertThatIllegalStateException().isThrownBy { counts.adjust(7, 1) }
    }

    companion object {
        private val NOW: LocalDateTime = LocalDateTime.of(2032, 1, 2, 3, 4, 5, 123456000)
    }
}
