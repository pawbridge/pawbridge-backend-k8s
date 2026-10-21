package com.pawbridge.backend.animal.internal.travel.infrastructure.collection;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TourApiPropertiesTest {
    @Test void givenDefaults__whenConfigured__thenUseBoundedCollectionAndFourteenDayRefresh() {
        var properties=new TourApiProperties();
        assertThat(properties.getMaxPagesPerRun()).isEqualTo(10);
        assertThat(properties.getMaxDetailsPerRun()).isEqualTo(18);
        assertThat(properties.getMaxVisitDetailsPerRun()).isEqualTo(18);
        assertThat(properties.getDailyRequestLimit()).isEqualTo(900);
        assertThat(properties.getDetailRefreshDays()).isEqualTo(14);
        assertThat(properties.isBulkPetEnabled()).isFalse();
        assertThat(properties.getMaxBulkPagesPerRun()).isEqualTo(10);
        assertThat(properties.getKoreanServiceKey()).isEmpty();
    }
    @Test void givenNonpositiveRefreshDays__whenConfigured__thenReject() {
        for (int days : new int[]{0,-1}) {
            assertThatThrownBy(()->new TourApiProperties().setDetailRefreshDays(days))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void givenVisitDetailBatchOutsideBounds__whenConfigured__thenReject() {
        for (int limit : new int[]{0,101}) {
            assertThatThrownBy(()->new TourApiProperties().setMaxVisitDetailsPerRun(limit))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void givenBudgetAboveCeiling__whenConfigured__thenReject() {
        for (int limit : new int[]{0,1001}) {
            assertThatThrownBy(()->new TourApiProperties().setDailyRequestLimit(limit)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
