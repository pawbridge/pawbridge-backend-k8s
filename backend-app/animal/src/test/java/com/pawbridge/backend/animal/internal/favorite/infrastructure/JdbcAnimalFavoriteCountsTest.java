package com.pawbridge.backend.animal.internal.favorite.infrastructure;

import static com.pawbridge.backend.animal.internal.favorite.infrastructure.JdbcAnimalFavoriteCounts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Calendar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.support.SqlValue;

class JdbcAnimalFavoriteCountsTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2032, 1, 2, 3, 4, 5, 123456000);
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final JdbcAnimalFavoriteCounts counts = new JdbcAnimalFavoriteCounts(jdbc, () -> NOW);

    @Test
    void given__an_existing_animal__when__incrementing__then__update_count_and_utc_updated_at_in_one_statement() throws Exception {
        when(jdbc.update(eq(INCREMENT), any(SqlValue.class), eq(7L))).thenReturn(1);
        assertThat(counts.adjust(7, 1)).isTrue();
        var time = ArgumentCaptor.forClass(SqlValue.class);
        verify(jdbc).update(eq(INCREMENT), time.capture(), eq(7L));
        var statement = mock(PreparedStatement.class);
        time.getValue().setValue(statement, 1);
        var calendar = ArgumentCaptor.forClass(Calendar.class);
        verify(statement).setTimestamp(eq(1), eq(Timestamp.valueOf(NOW)), calendar.capture());
        assertThat(calendar.getValue().getTimeZone().getID()).isEqualTo("UTC");
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void given__an_existing_animal__when__decrementing__then__use_the_floor_at_zero_statement() {
        when(jdbc.update(eq(DECREMENT), any(SqlValue.class), eq(7L))).thenReturn(1);
        assertThat(counts.adjust(7, -1)).isTrue();
        assertThat(DECREMENT).contains("CASE WHEN favorite_count>0 THEN favorite_count-1 ELSE favorite_count END")
                .contains("updated_at=CASE WHEN favorite_count>0 THEN ? ELSE updated_at END");
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void given__no_matching_animal__when__adjusting__then__report_absence_without_failing(int delta) {
        when(jdbc.update(anyString(), any(SqlValue.class), anyLong())).thenReturn(0);
        assertThat(counts.adjust(7, delta)).isFalse();
    }

    @ParameterizedTest @ValueSource(ints = {0, 2, -2})
    void given__an_unsupported_delta__when__adjusting__then__reject_before_any_sql(int delta) {
        assertThatIllegalArgumentException().isThrownBy(() -> counts.adjust(7, delta));
        verifyNoInteractions(jdbc);
    }

    @Test
    void given__an_impossible_row_count__when__adjusting__then__fail_instead_of_reporting_success() {
        when(jdbc.update(anyString(), any(SqlValue.class), anyLong())).thenReturn(2);
        assertThatIllegalStateException().isThrownBy(() -> counts.adjust(7, 1));
    }
}
