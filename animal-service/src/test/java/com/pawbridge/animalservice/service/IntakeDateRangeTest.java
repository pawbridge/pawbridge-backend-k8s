package com.pawbridge.animalservice.service;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class IntakeDateRangeTest {
    private final LocalDate date = LocalDate.of(2026, 9, 27);

    @Test void accepts_inclusive_single_day_and_366_day_range() {
        assertThat(new IntakeDateRange(date, date).from()).isEqualTo(date);
        assertThat(new IntakeDateRange(date.minusDays(365), date).to()).isEqualTo(date);
    }

    @Test void rejects_partial_inverted_and_excessive_ranges() {
        assertThatIllegalArgumentException().isThrownBy(() -> new IntakeDateRange(null, date));
        assertThatIllegalArgumentException().isThrownBy(() -> new IntakeDateRange(date, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new IntakeDateRange(date, date.minusDays(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new IntakeDateRange(date.minusDays(366), date));
        assertThatIllegalArgumentException().isThrownBy(() -> new IntakeDateRange(LocalDate.of(0,1,1), LocalDate.of(0,1,2)));
    }
}
